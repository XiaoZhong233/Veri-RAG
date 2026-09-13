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
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** 微信客服 access_token、消息同步与文本回复客户端。 */
@Component
@ConditionalOnProperty(prefix = "wecom.kf", name = "enabled", havingValue = "true")
public class WeComKfApiClient {

    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(20);
    private final WeComKfProperties properties;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;
    private volatile AccessToken accessToken;
    private volatile AccessToken contactsAccessToken;

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
        postWithAccessToken("/cgi-bin/kf/send_msg", body, true, true);
    }

    public int getServiceState(String openKfId, String externalUserId) {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("open_kfid", requireText(openKfId, "open_kfid"));
        body.put("external_userid", requireText(externalUserId, "external_userid"));
        JsonNode response = postWithAccessToken(
                "/cgi-bin/kf/service_state/get", body, true);
        return response.path("service_state").asInt(-1);
    }

    public List<KfAccount> listAccounts() {
        JsonNode response = getWithAccessToken("/cgi-bin/kf/account/list", true);
        List<KfAccount> accounts = new ArrayList<>();
        response.path("account_list").forEach(item -> accounts.add(new KfAccount(
                item.path("open_kfid").asText(""),
                item.path("name").asText(""),
                item.path("avatar").asText(""))));
        return List.copyOf(accounts);
    }

    /** 客户资料使用微信客服凭证，不使用通讯录凭证；只取后台展示所需字段。 */
    public KfCustomer customer(String externalUserId) {
        KfCustomer customer = customers(List.of(externalUserId)).get(externalUserId);
        if(customer == null) throw new IllegalStateException("微信暂未返回该客户的基础资料");
        return customer;
    }

    public java.util.Map<String, KfCustomer> customers(List<String> externalUserIds) {
        if(externalUserIds.isEmpty() || externalUserIds.size()>100) throw new IllegalArgumentException("客户批次大小不合法");
        ObjectNode body = objectMapper.createObjectNode();
        var ids=body.putArray("external_userid_list");
        externalUserIds.forEach(id -> ids.add(requireText(id,"external_userid")));
        JsonNode response = postWithAccessToken("/cgi-bin/kf/customer/batchget", body, true);
        var result = new java.util.HashMap<String, KfCustomer>();
        for (JsonNode item : response.path("customer_list")) {
            String id=item.path("external_userid").asText("");
            if (externalUserIds.contains(id)) {
                result.put(id,new KfCustomer(item.path("nickname").asText(""), item.path("avatar").asText("")));
            }
        }
        return java.util.Map.copyOf(result);
    }

    public record KfCustomer(String nickname, String avatar) {}

    public List<KfServicer> listServicers(String openKfId) {
        String path = "/cgi-bin/kf/servicer/list?open_kfid="
                + encode(requireText(openKfId, "open_kfid"));
        JsonNode response = getWithAccessToken(path, true);
        List<KfServicer> servicers = new ArrayList<>();
        response.path("servicer_list").forEach(item -> servicers.add(new KfServicer(
                item.path("userid").asText(""),
                item.path("department_id").asLong(0L),
                item.path("status").asInt(-1))));
        return List.copyOf(servicers);
    }

    /** 获取当前应用通讯录可见范围内的成员 userid，供后台选择接待人员。 */
    public List<KfMemberId> listVisibleMemberIds() {
        List<KfMemberId> members = new ArrayList<>();
        Set<String> visitedCursors = new HashSet<>();
        String cursor = "";
        do {
            if (!visitedCursors.add(cursor)) {
                throw new IllegalStateException("WeCom member list returned a repeated cursor");
            }
            ObjectNode body = objectMapper.createObjectNode();
            if (StringUtils.hasText(cursor)) {
                body.put("cursor", cursor);
            }
            body.put("limit", 10000);
            JsonNode response = postWithContactsAccessToken(
                    "/cgi-bin/user/list_id", body, true);
            response.path("dept_user").forEach(item -> {
                List<Long> departmentIds = new ArrayList<>();
                item.path("department").forEach(department ->
                        departmentIds.add(department.asLong()));
                members.add(new KfMemberId(
                        item.path("userid").asText(""), List.copyOf(departmentIds)));
            });
            cursor = response.path("next_cursor").asText("");
        } while (StringUtils.hasText(cursor));
        return members.stream()
                .filter(member -> StringUtils.hasText(member.userId()))
                .distinct()
                .toList();
    }

    public List<KfServicerResult> addServicers(String openKfId, List<String> userIds) {
        return changeServicers("/cgi-bin/kf/servicer/add", openKfId, userIds);
    }

    public List<KfServicerResult> removeServicers(String openKfId, List<String> userIds) {
        return changeServicers("/cgi-bin/kf/servicer/del", openKfId, userIds);
    }

    private List<KfServicerResult> changeServicers(
            String path, String openKfId, List<String> userIds) {
        if (userIds == null || userIds.isEmpty() || userIds.size() > 100) {
            throw new IllegalArgumentException("userid_list size must be between 1 and 100");
        }
        ObjectNode body = objectMapper.createObjectNode();
        body.put("open_kfid", requireText(openKfId, "open_kfid"));
        var array = body.putArray("userid_list");
        userIds.stream().map(id -> requireText(id, "userid")).distinct().forEach(array::add);
        JsonNode response = postWithAccessToken(path, body, true);
        List<KfServicerResult> results = new ArrayList<>();
        response.path("result_list").forEach(item -> results.add(new KfServicerResult(
                item.path("userid").asText(""),
                item.path("errcode").asInt(-1),
                item.path("errmsg").asText(""))));
        return List.copyOf(results);
    }

    public void transitionToAssistant(String openKfId, String externalUserId) {
        transitionServiceState(openKfId, externalUserId, 1);
    }

    /** 将会话直接分配给指定且处于“正在接待”的企业微信成员。 */
    public String transitionToHuman(
            String openKfId, String externalUserId, String servicerUserId) {
        ObjectNode body = serviceStateBody(openKfId, externalUserId, 3);
        body.put("servicer_userid", requireText(servicerUserId, "servicer_userid"));
        return postWithAccessToken("/cgi-bin/kf/service_state/trans", body, true)
                .path("msg_code").asText("");
    }

    /** 结束旧会话，并返回用于发送结束提示语的事件消息 code。 */
    public String transitionToEnded(String openKfId, String externalUserId) {
        return transitionServiceState(openKfId, externalUserId, 4)
                .path("msg_code").asText("");
    }

    public void sendEventText(String code, String messageId, String content) {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("code", requireText(code, "msg_code"));
        body.put("msgid", requireText(messageId, "msgid"));
        body.put("msgtype", "text");
        body.putObject("text").put("content", requireText(content, "message content"));
        postWithAccessToken("/cgi-bin/kf/send_msg_on_event", body, true, true);
    }

    private JsonNode transitionServiceState(
            String openKfId, String externalUserId, int serviceState) {
        return postWithAccessToken("/cgi-bin/kf/service_state/trans",
                serviceStateBody(openKfId, externalUserId, serviceState), true);
    }

    private ObjectNode serviceStateBody(
            String openKfId, String externalUserId, int serviceState) {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("open_kfid", requireText(openKfId, "open_kfid"));
        body.put("external_userid", requireText(externalUserId, "external_userid"));
        body.put("service_state", serviceState);
        return body;
    }

    private JsonNode getWithAccessToken(String path, boolean retryInvalidToken) {
        String separator = path.contains("?") ? "&" : "?";
        JsonNode response = get(path + separator + "access_token=" + encode(getAccessToken()));
        int errorCode = response.path("errcode").asInt(-1);
        if (retryInvalidToken && (errorCode == 40014 || errorCode == 42001)) {
            accessToken = null;
            response = get(path + separator + "access_token=" + encode(getAccessToken()));
            errorCode = response.path("errcode").asInt(-1);
        }
        if (errorCode != 0) {
            throw apiError(path, response);
        }
        return response;
    }

    private JsonNode postWithAccessToken(String path, JsonNode body, boolean retryInvalidToken) {
        return postWithAccessToken(path, body, retryInvalidToken, false);
    }

    private JsonNode postWithAccessToken(
            String path, JsonNode body, boolean retryInvalidToken,
            boolean acceptRepeatedMessageId) {
        JsonNode response = post(path + "?access_token=" + encode(getAccessToken()), body);
        int errorCode = response.path("errcode").asInt(-1);
        if (retryInvalidToken && (errorCode == 40014 || errorCode == 42001)) {
            accessToken = null;
            response = post(path + "?access_token=" + encode(getAccessToken()), body);
            errorCode = response.path("errcode").asInt(-1);
        }
        // msgid 是客户端幂等键。95033 表示此前请求已被企业微信接受，按成功处理即可。
        if (errorCode != 0 && !(acceptRepeatedMessageId && errorCode == 95033)) {
            throw apiError(path, response);
        }
        return response;
    }

    private JsonNode postWithContactsAccessToken(
            String path, JsonNode body, boolean retryInvalidToken) {
        JsonNode response = post(path + "?access_token="
                + encode(getContactsAccessToken()), body);
        int errorCode = response.path("errcode").asInt(-1);
        if (retryInvalidToken && (errorCode == 40014 || errorCode == 42001)) {
            contactsAccessToken = null;
            response = post(path + "?access_token="
                    + encode(getContactsAccessToken()), body);
            errorCode = response.path("errcode").asInt(-1);
        }
        if (errorCode != 0) {
            throw apiError(path, response);
        }
        return response;
    }

    private String getAccessToken() {
        return getAccessToken(false);
    }

    private String getContactsAccessToken() {
        if (!StringUtils.hasText(properties.getContactsSecret())) {
            throw new IllegalStateException(
                    "未配置 WECOM_CONTACTS_SECRET，请在客服管理页手工输入 userid");
        }
        return getAccessToken(true);
    }

    private String getAccessToken(boolean contacts) {
        AccessToken current = contacts ? contactsAccessToken : accessToken;
        Instant now = Instant.now();
        if (current != null && now.isBefore(current.expiresAt())) {
            return current.value();
        }
        synchronized (this) {
            current = contacts ? contactsAccessToken : accessToken;
            now = Instant.now();
            if (current != null && now.isBefore(current.expiresAt())) {
                return current.value();
            }
            String secret = contacts
                    ? properties.getContactsSecret() : properties.getSecret();
            String configName = contacts
                    ? "wecom.kf.contacts-secret" : "wecom.kf.secret";
            String path = "/cgi-bin/gettoken?corpid=" + encode(properties.getCorpId())
                    + "&corpsecret=" + encode(requireText(secret, configName));
            JsonNode response = get(path);
            if (response.path("errcode").asInt(-1) != 0) {
                throw apiError("/cgi-bin/gettoken", response);
            }
            String value = response.path("access_token").asText("");
            if (!StringUtils.hasText(value)) {
                throw new IllegalStateException("WeCom gettoken returned an empty access_token");
            }
            long expiresIn = Math.max(response.path("expires_in").asLong(7200L) - 300L, 60L);
            AccessToken token = new AccessToken(
                    value, Instant.now().plusSeconds(expiresIn));
            if (contacts) {
                contactsAccessToken = token;
            } else {
                accessToken = token;
            }
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

    public record KfAccount(String openKfId, String name, String avatar) {
    }

    public record KfServicer(String userId, long departmentId, int status) {
        public boolean active() {
            return StringUtils.hasText(userId) && status == 0;
        }
    }

    public record KfMemberId(String userId, List<Long> departmentIds) {
    }

    public record KfServicerResult(String userId, int errorCode, String errorMessage) {
    }
}
