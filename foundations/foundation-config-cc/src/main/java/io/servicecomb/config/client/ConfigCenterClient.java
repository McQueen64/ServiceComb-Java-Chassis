/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.servicecomb.config.client;

import java.io.IOException;
import java.io.InputStream;
import java.io.UnsupportedEncodingException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import org.apache.commons.lang.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.core.type.TypeReference;

import io.netty.handler.codec.http.HttpResponseStatus;
import io.servicecomb.config.archaius.sources.ConfigCenterConfigurationSourceImpl;
import io.servicecomb.foundation.auth.AuthHeaderProvider;
import io.servicecomb.foundation.auth.SignRequest;
import io.servicecomb.foundation.common.net.IpPort;
import io.servicecomb.foundation.common.net.NetUtils;
import io.servicecomb.foundation.common.utils.JsonUtils;
import io.servicecomb.foundation.ssl.SSLCustom;
import io.servicecomb.foundation.ssl.SSLOption;
import io.servicecomb.foundation.ssl.SSLOptionFactory;
import io.servicecomb.foundation.vertx.VertxTLSBuilder;
import io.servicecomb.foundation.vertx.VertxUtils;
import io.servicecomb.foundation.vertx.client.ClientPoolManager;
import io.servicecomb.foundation.vertx.client.http.HttpClientVerticle;
import io.servicecomb.foundation.vertx.client.http.HttpClientWithContext;
import io.vertx.core.DeploymentOptions;
import io.vertx.core.Vertx;
import io.vertx.core.http.CaseInsensitiveHeaders;
import io.vertx.core.http.HttpClientOptions;
import io.vertx.core.http.HttpClientRequest;
import io.vertx.core.http.WebSocket;
import io.vertx.core.http.impl.FrameType;
import io.vertx.core.http.impl.ws.WebSocketFrameImpl;

/**
 * Created by on 2016/5/17.
 */

public class ConfigCenterClient {

  private static final Logger LOGGER = LoggerFactory.getLogger(ConfigCenterClient.class);

  private static final ConfigCenterConfig CONFIG_CENTER_CONFIG = ConfigCenterConfig.INSTANCE;

  private static final String SSL_KEY = "cc.consumer";

  private static final ScheduledExecutorService EXECUTOR = Executors.newScheduledThreadPool(1);

  private static final long HEARTBEAT_INTERVAL = 30000;

  private ScheduledExecutorService heartbeatTask = null;

  private int refreshMode = CONFIG_CENTER_CONFIG.getRefreshMode();

  private int refreshInterval = CONFIG_CENTER_CONFIG.getRefreshInterval();

  private int firstRefreshInterval = CONFIG_CENTER_CONFIG.getFirstRefreshInterval();

  private int refreshPort = CONFIG_CENTER_CONFIG.getRefreshPort();

  private String tenantName = CONFIG_CENTER_CONFIG.getTenantName();

  private String serviceName = CONFIG_CENTER_CONFIG.getServiceName();

  private List<String> serverUri = CONFIG_CENTER_CONFIG.getServerUri();

  private ConfigCenterConfigurationSourceImpl.UpdateHandler updateHandler;

  private static ClientPoolManager<HttpClientWithContext> clientMgr = new ClientPoolManager<>();

  private boolean isWatching = false;

  private static final ServiceLoader<AuthHeaderProvider> authHeaderProviders =
      ServiceLoader.load(AuthHeaderProvider.class);

  public ConfigCenterClient(ConfigCenterConfigurationSourceImpl.UpdateHandler updateHandler) {
    this.updateHandler = updateHandler;
  }

  public void connectServer() {
    if (refreshMode != 0 && refreshMode != 1) {
      LOGGER.error("refreshMode must be 0 or 1.");
      return;
    }
    ParseConfigUtils parseConfigUtils = new ParseConfigUtils(updateHandler);
    try {
      deployConfigClient();
    } catch (InterruptedException e) {
      throw new IllegalStateException(e);
    }
    MemberDiscovery memberdis = new MemberDiscovery(serverUri);
    refreshMembers(memberdis);
    EXECUTOR.scheduleWithFixedDelay(new ConfigRefresh(parseConfigUtils, memberdis), firstRefreshInterval,
        refreshInterval, TimeUnit.MILLISECONDS);
  }

  private void refreshMembers(MemberDiscovery memberDiscovery) {
    if (CONFIG_CENTER_CONFIG.getAutoDiscoveryEnabled()) {
      String configCenter = memberDiscovery.getConfigServer();
      IpPort ipPort = NetUtils.parseIpPortFromURI(configCenter);
      clientMgr.findThreadBindClientPool().runOnContext(client -> {
        HttpClientRequest request = client.get(ipPort.getPort(), ipPort.getHostOrIp(), URIConst.MEMBERS, rsp -> {
          if (rsp.statusCode() == HttpResponseStatus.OK.code()) {
            rsp.bodyHandler(buf -> {
              memberDiscovery.refreshMembers(buf.toJsonObject());
            });
          }
        });
        SignRequest signReq = createSignRequest(request.method().toString(),
            configCenter + URIConst.MEMBERS, new HashMap<>(), null);
        if (ConfigCenterConfig.INSTANCE.getToken() != null) {
          request.headers().add("X-Auth-Token", ConfigCenterConfig.INSTANCE.getToken());
        }
        authHeaderProviders.forEach(provider -> request.headers().addAll(provider.getSignAuthHeaders(signReq)));
        request.end();
      });
    }
  }

  private void deployConfigClient() throws InterruptedException {
    Vertx vertx = VertxUtils.getOrCreateVertxByName("config-center", null);
    HttpClientOptions httpClientOptions = createHttpClientOptions();
    DeploymentOptions deployOptions = VertxUtils.createClientDeployOptions(clientMgr, 1, 1, httpClientOptions);
    VertxUtils.blockDeploy(vertx, HttpClientVerticle.class, deployOptions);
  }

  private HttpClientOptions createHttpClientOptions() {
    HttpClientOptions httpClientOptions = new HttpClientOptions();
    httpClientOptions.setConnectTimeout(CONFIG_CENTER_CONFIG.getConnectionTimeout());
    if (serverUri.get(0).toLowerCase().startsWith("https")) {
      LOGGER.debug("service center client performs requests over TLS");
      SSLOptionFactory factory = SSLOptionFactory.createSSLOptionFactory(SSL_KEY,
          ConfigCenterConfig.INSTANCE.getConcurrentCompositeConfiguration());
      SSLOption sslOption;
      if (factory == null) {
        sslOption = SSLOption.buildFromYaml(SSL_KEY,
            ConfigCenterConfig.INSTANCE.getConcurrentCompositeConfiguration());
      } else {
        sslOption = factory.createSSLOption();
      }
      SSLCustom sslCustom = SSLCustom.createSSLCustom(sslOption.getSslCustomClass());
      VertxTLSBuilder.buildHttpClientOptions(sslOption, sslCustom, httpClientOptions);
    }
    return httpClientOptions;
  }

  class ConfigRefresh implements Runnable {
    private ParseConfigUtils parseConfigUtils;

    private MemberDiscovery memberdis;

    ConfigRefresh(ParseConfigUtils parseConfigUtils, MemberDiscovery memberdis) {
      this.parseConfigUtils = parseConfigUtils;
      this.memberdis = memberdis;
    }

    // 具体动作
    @Override
    public void run() {
      // this will be single threaded, so we don't care about concurrent
      // staffs
      try {
        String configCenter = memberdis.getConfigServer();
        if (refreshMode == 1) {
          refreshConfig(configCenter);
        } else if (!isWatching) {
          // 重新监听时需要先加载，避免在断开期间丢失变更
          refreshConfig(configCenter);
          doWatch(configCenter);
        }
      } catch (Exception e) {
        LOGGER.error("client refresh thread exception", e);
      }
    }

    // create watch and wait for done
    public void doWatch(String configCenter)
        throws URISyntaxException, UnsupportedEncodingException, InterruptedException {
      CountDownLatch waiter = new CountDownLatch(1);
      IpPort ipPort = NetUtils.parseIpPortFromURI(configCenter);
      String url = URIConst.REFRESH_ITEMS + "?dimensionsInfo="
          + StringUtils.deleteWhitespace(URLEncoder.encode(serviceName, "UTF-8"));
      Map<String, String> headers = new HashMap<>();
      headers.put("x-domain-name", tenantName);
      if (ConfigCenterConfig.INSTANCE.getToken() != null) {
        headers.put("X-Auth-Token", ConfigCenterConfig.INSTANCE.getToken());
      }

      HttpClientWithContext vertxHttpClient = clientMgr.findThreadBindClientPool();
      vertxHttpClient.runOnContext(client -> {
        Map<String, String> authHeaders = new HashMap<>();
        authHeaderProviders.forEach(provider -> authHeaders.putAll(provider.getSignAuthHeaders(
            createSignRequest(null, configCenter + url, headers, null))));

        client.websocket(refreshPort, ipPort.getHostOrIp(), url,
            new CaseInsensitiveHeaders().addAll(headers)
                .addAll(authHeaders),
            ws -> {
              ws.exceptionHandler(e -> {
                LOGGER.error("watch config read fail", e);
                stopHeartBeatThread();
                isWatching = false;
              });
              ws.closeHandler(v -> {
                LOGGER.warn("watching config connection is closed accidentally");
                stopHeartBeatThread();
                isWatching = false;
              });
              ws.handler(action -> {
                LOGGER.info("watching config recieved {}", action);
                Map<String, Object> mAction = action.toJsonObject().getMap();
                if ("CREATE".equals(mAction.get("action"))) {
                  refreshConfig(configCenter);
                } else if ("MEMBER_CHANGE".equals(mAction.get("action"))) {
                  refreshMembers(memberdis);
                } else {
                  parseConfigUtils.refreshConfigItemsIncremental(mAction);
                }
              });
              startHeartBeatThread(ws);
              isWatching = true;
              waiter.countDown();
            },
            e -> {
              LOGGER.error("watcher connect to config center {} failed: {}", serverUri, e.getMessage());
              waiter.countDown();
            });
      });
      waiter.await();
    }

    private void startHeartBeatThread(WebSocket ws) {
      heartbeatTask = Executors.newScheduledThreadPool(1);
      heartbeatTask.scheduleWithFixedDelay(() -> sendHeartbeat(ws), HEARTBEAT_INTERVAL, HEARTBEAT_INTERVAL,
          TimeUnit.MILLISECONDS);
    }

    private void stopHeartBeatThread() {
      if (heartbeatTask != null) {
        heartbeatTask.shutdownNow();
      }
    }

    private void sendHeartbeat(WebSocket ws) {
      try {
        ws.writeFrame(new WebSocketFrameImpl(FrameType.PING));
      } catch (IllegalStateException e) {
        LOGGER.error("heartbeat fail", e);
      }
    }

    public void refreshConfig(String configcenter) {
      clientMgr.findThreadBindClientPool().runOnContext(client -> {
        String path = URIConst.ITEMS + "?dimensionsInfo=" + StringUtils.deleteWhitespace(serviceName);
        IpPort ipPort = NetUtils.parseIpPortFromURI(configcenter);
        HttpClientRequest request = client.get(ipPort.getPort(), ipPort.getHostOrIp(), path, rsp -> {
          if (rsp.statusCode() == HttpResponseStatus.OK.code()) {
            rsp.bodyHandler(buf -> {
              try {
                parseConfigUtils
                    .refreshConfigItems(JsonUtils.OBJ_MAPPER.readValue(buf.toString(),
                        new TypeReference<LinkedHashMap<String, Map<String, String>>>() {
                        }));
              } catch (IOException e) {
                LOGGER.error("config refresh result parse fail", e);
              }
            });
          } else {
            LOGGER.error("fetch config fail");
          }
        });
        Map<String, String> headers = new HashMap<>();
        headers.put("x-domain-name", tenantName);
        if (ConfigCenterConfig.INSTANCE.getToken() != null) {
          headers.put("X-Auth-Token", ConfigCenterConfig.INSTANCE.getToken());
        }
        request.headers().addAll(headers);
        authHeaderProviders.forEach(provider -> request.headers()
            .addAll(provider.getSignAuthHeaders(createSignRequest(request.method().toString(),
                configcenter + path, headers, null))));
        request.exceptionHandler(e -> {
          LOGGER.error("config refresh fail {}", e.getMessage());
        });
        request.end();
      });
    }
  }

  public static SignRequest createSignRequest(String method, String endpoint, Map<String, String> headers,
      InputStream content) {
    SignRequest signReq = new SignRequest();
    try {
      signReq.setEndpoint(new URI(endpoint));
    } catch (URISyntaxException e) {
      LOGGER.warn("set uri failed, uri is {}, message: {}", endpoint.toString(), e.getMessage());
    }

    Map<String, String[]> queryParams = new HashMap<>();
    if (endpoint.contains("?")) {
      String parameters = endpoint.substring(endpoint.indexOf("?") + 1);
      if (null != parameters && !"".equals(parameters)) {
        String[] parameterarray = parameters.split("&");
        for (String p : parameterarray) {
          String key = p.split("=")[0];
          String value = p.split("=")[1];
          if (!queryParams.containsKey(key)) {
            queryParams.put(key, new String[] {value});
          } else {
            List<String> vals = new ArrayList<>(Arrays.asList(queryParams.get(key)));
            vals.add(value);
            queryParams.put(key, vals.toArray(new String[vals.size()]));
          }
        }
      }
    }
    signReq.setQueryParams(queryParams);

    signReq.setHeaders(headers);
    signReq.setHttpMethod(method);
    signReq.setContent(content);
    return signReq;
  }
}
