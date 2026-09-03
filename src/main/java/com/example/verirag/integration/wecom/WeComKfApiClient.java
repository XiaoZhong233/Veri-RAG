package com.example.verirag.integration.wecom;

import com.example.verirag.config.WeComKfProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;

/** 微信客服 access_token、消息同步与文本回复客户端。 */
@Component
@ConditionalOnProperty(prefix = "wecom.kf", name = "enabled", havingValue = "true")
public class WeComKfApiClient {

    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(20);
    private final WeComKfProperties properties;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;
    private volatile AccessToken accessToken;

    public WeComKfApiClient(WeComKfProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
        requireText(properties.getCorpId(), "wecom.kf.corp-id");
        if (properties.getApiBaseUrl() == null
                || !"https".equalsIgnoreCase(properties.getApiBaseUrl().getScheme())) {
            throw new IllegalStateException("wecom.kf.api-base-url must use HTTPS");
        }
        if (properties.getSyncLimit() < 1 || properties.getSyncLimit() > 1000) {
            throw new IllegalStateException("wecom.kf.sync-limit must be between 1 and 1000");
        }
    }

    public JsonNode syncMessages(String openKfId, String callbackToken, String cursor) {
        ObjectNode body = objectMapper.createObjectNode();
        if (StringUtils.hasText(cursor)) {
            body.put("cursor", cursor);
        }
        body.put("token", requireText(callbackToken, "callback token"));
        body.put("limit", properties.getSyncLimit());
        body.put("voice_format", 0);
        body.put("open_kfid", requireText(openKfId, "open_kfid"));
        return postWithAccessToken("/cgi-bin/kf/sync_msg", body, true);
    }

    public void sendText(String openKfId, String externalUserId, String messageId, String content) {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("touser", requireText(externalUserId, "external_userid"));
        body.put("open_kfid", requireText(openKfId, "open_kfid"));
        body.put("msgid", requireText(messageId, "msgid"));
        body.put("msgtype", "text");
        body.putObject("text").put("content", requireText(content, "message content"));
        postWithAccessToken("/cgi-bin/kf/send_msg", body, true);
    }

    public int getServiceState(String openKfId, String externalUserId) {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("open_kfid", requireText(openKfId, "open_kfid"));
        body.put("external_userid", requireText(externalUserId, "external_userid"));
        JsonNode response = postWithAccessToken(
                "/cgi-bin/kf/service_state/get", body, true);
        return response.path("service_state").asInt(-1);
    }

    public void transitionToAssistant(String openKfId, String externalUserId) {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("open_kfid", requireText(openKfId, "open_kfid"));
        body.put("external_userid", requireText(externalUserId, "external_userid"));
        body.put("service_state", 1);
        postWithAccessToken("/cgi-bin/kf/service_state/trans", body, true);
    }

    private JsonNode postWithAccessToken(String path, JsonNode body, boolean retryInvalidToken) {
        JsonNode response = post(path + "?access_token=" + encode(getAccessToken()), body);
        int errorCode = response.path("errcode").asInt(-1);
        if (retryInvalidToken && (errorCode == 40014 || errorCode == 42001)) {
            accessToken = null;
            response = post(path + "?access_token=" + encode(getAccessToken()), body);
            errorCode = response.path("errcode").asInt(-1);
        }
        if (errorCode != 0) {
            throw apiError(path, response);
        }
        return response;
    }

    private String getAccessToken() {
        AccessToken current = accessToken;
        Instant now = Instant.now();
        if (current != null && now.isBefore(current.expiresAt())) {
            return current.value();
        }
        synchronized (this) {
            current = accessToken;
            now = Instant.now();
            if (current != null && now.isBefore(current.expiresAt())) {
                return current.value();
            }
            String path = "/cgi-bin/gettoken?corpid=" + encode(properties.getCorpId())
                    + "&corpsecret=" + encode(requireText(
                            properties.getSecret(), "wecom.kf.secret"));
            JsonNode response = get(path);
            if (response.path("errcode").asInt(-1) != 0) {
                throw apiError("/cgi-bin/gettoken", response);
            }
            String value = response.path("access_token").asText("");
            if (!StringUtils.hasText(value)) {
                throw new IllegalStateException("WeCom gettoken returned an empty access_token");
            }
            long expiresIn = Math.max(response.path("expires_in").asLong(7200L) - 300L, 60L);
            accessToken = new AccessToken(value, Instant.now().plusSeconds(expiresIn));
            return value;
        }
    }

    private JsonNode get(String path) {
        HttpRequest request = HttpRequest.newBuilder(resolve(path))
                .timeout(REQUEST_TIMEOUT)
                .GET()
                .build();
        return exchange(request);
    }

    private JsonNode post(String path, JsonNode body) {
        HttpRequest request = HttpRequest.newBuilder(resolve(path))
                .timeout(REQUEST_TIMEOUT)
                .header("Content-Type", "application/json; charset=UTF-8")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8))
                .build();
        return exchange(request);
    }

    private JsonNode exchange(HttpRequest request) {
        try {
            HttpResponse<String> response = httpClient.send(
                    request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IllegalStateException(
                        "WeCom API HTTP status " + response.statusCode());
            }
            return objectMapper.readTree(response.body());
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("WeCom API request interrupted", ex);
        } catch (Exception ex) {
            if (ex instanceof IllegalStateException stateException) {
                throw stateException;
            }
            throw new IllegalStateException("WeCom API request failed", ex);
        }
    }

    private URI resolve(String path) {
        return properties.getApiBaseUrl().resolve(path);
    }

    private static IllegalStateException apiError(String path, JsonNode response) {
        return new IllegalStateException("WeCom API " + path + " failed: errcode="
                + response.path("errcode").asInt(-1) + ", errmsg="
                + response.path("errmsg").asText("unknown"));
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static String requireText(String value, String name) {
        if (!StringUtils.hasText(value)) {
            throw new IllegalStateException(name + " must not be blank");
        }
        return value.trim();
    }

    private record AccessToken(String value, Instant expiresAt) {
    }
}
