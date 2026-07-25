package com.example.verirag.kdocs;

import com.example.verirag.exception.BusinessException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 金山文档 Web OAuth：生成授权地址，并在回调中以授权码换取服务端令牌。
 * <p>
 * 当前为单账号 Demo 实现，令牌保存到本地 Redis；服务重启后仍可继续使用未过期令牌。
 */
@Service
@RequiredArgsConstructor
public class KdocsOAuthService {

    private static final String AUTHORIZATION_ENDPOINT = "https://developer.kdocs.cn/h5/auth";
    private static final String TOKEN_ENDPOINT = "https://developer.kdocs.cn/api/v1/oauth2/access_token";
    private static final Duration STATE_TTL = Duration.ofMinutes(10);
    private static final Duration REFRESH_TOKEN_STORE_TTL = Duration.ofDays(90);
    private static final String TOKEN_REDIS_KEY = "veri-rag:kdocs:oauth:token";

    private final KdocsOAuthProperties properties;
    private final StringRedisTemplate stringRedisTemplate;
    private final ObjectMapper objectMapper;
    private final RestClient restClient = RestClient.create();
    private final SecureRandom secureRandom = new SecureRandom();
    private final Map<String, Instant> pendingStates = new ConcurrentHashMap<>();

    /** 创建一次性 OAuth state，并生成让浏览器跳转的金山授权页地址。 */
    public String createAuthorizationUrl() {
        verifyConfigured();
        pendingStates.entrySet().removeIf(entry -> entry.getValue().isBefore(Instant.now()));
        String state = createState();
        pendingStates.put(state, Instant.now().plus(STATE_TTL));
        return UriComponentsBuilder.fromUriString(AUTHORIZATION_ENDPOINT)
                .queryParam("app_id", properties.getAppId())
                .queryParam("scope", properties.getScope())
                .queryParam("redirect_uri", properties.getRedirectUri())
                .queryParam("state", state)
                .build()
                .encode()
                .toUriString();
    }

    /** 校验 state 后，使用金山回调的 code 获取 access_token 与 refresh_token。 */
    public TokenStatus exchangeCode(String code, String state) {
        verifyConfigured();
        verifyState(state);
        if (!StringUtils.hasText(code)) {
            throw new BusinessException("金山文档授权失败：缺少授权码");
        }

        JsonNode body;
        try {
            body = restClient.get()
                    .uri(UriComponentsBuilder.fromUriString(TOKEN_ENDPOINT)
                            .queryParam("code", code)
                            .queryParam("app_id", properties.getAppId())
                            .queryParam("app_key", properties.getAppKey())
                            .build()
                            .encode()
                            .toUri())
                    .header("Accept", "application/json")
                    .retrieve()
                    .body(JsonNode.class);
        }
        catch (Exception ex) {
            throw new BusinessException("金山文档获取授权令牌失败：" + ex.getMessage());
        }

        if (body == null || body.path("code").asInt(-1) != 0 || body.path("data").isMissingNode()) {
            String message = body == null ? "响应为空" : body.path("message").asText("未知错误");
            throw new BusinessException("金山文档获取授权令牌失败：" + message);
        }

        JsonNode data = body.path("data");
        String accessToken = data.path("access_token").asText();
        if (!StringUtils.hasText(accessToken)) {
            throw new BusinessException("金山文档获取授权令牌失败：响应中缺少 access_token");
        }
        long expiresIn = data.path("expires_in").asLong(0);
        OAuthToken token = new OAuthToken(accessToken, data.path("refresh_token").asText(null),
                Instant.now().plusSeconds(Math.max(expiresIn, 0)));
        saveToken(token, expiresIn);
        return status();
    }

    /** 供后续下载服务调用；不会将 token 暴露给浏览器。 */
    public String requireAccessToken() {
        OAuthToken token = loadToken();
        if (token == null || token.expiresAt().isBefore(Instant.now())) {
            throw new BusinessException("金山文档未授权或授权已过期，请重新授权");
        }
        return token.accessToken();
    }

    public TokenStatus status() {
        OAuthToken token = loadToken();
        if (token == null) {
            return new TokenStatus(false, null, false);
        }
        boolean expired = !token.expiresAt().isAfter(Instant.now());
        return new TokenStatus(!expired, token.expiresAt(), token.refreshToken() != null);
    }

    private void saveToken(OAuthToken token, long expiresInSeconds) {
        try {
            String value = objectMapper.writeValueAsString(Map.of(
                    "accessToken", token.accessToken(),
                    "refreshToken", token.refreshToken() == null ? "" : token.refreshToken(),
                    "expiresAt", token.expiresAt().toString()));
            // refresh_token 最长可用 90 天；若没有返回 refresh_token，则只保存至 access_token 过期。
            Duration ttl = StringUtils.hasText(token.refreshToken())
                    ? REFRESH_TOKEN_STORE_TTL
                    : Duration.ofSeconds(Math.max(expiresInSeconds, 1));
            stringRedisTemplate.opsForValue().set(TOKEN_REDIS_KEY, value, ttl);
        }
        catch (Exception ex) {
            throw new BusinessException("金山文档授权令牌保存到 Redis 失败：" + ex.getMessage());
        }
    }

    private OAuthToken loadToken() {
        try {
            String value = stringRedisTemplate.opsForValue().get(TOKEN_REDIS_KEY);
            if (!StringUtils.hasText(value)) {
                return null;
            }
            JsonNode node = objectMapper.readTree(value);
            String accessToken = node.path("accessToken").asText();
            String refreshToken = node.path("refreshToken").asText(null);
            String expiresAt = node.path("expiresAt").asText();
            if (!StringUtils.hasText(accessToken) || !StringUtils.hasText(expiresAt)) {
                stringRedisTemplate.delete(TOKEN_REDIS_KEY);
                return null;
            }
            return new OAuthToken(accessToken, StringUtils.hasText(refreshToken) ? refreshToken : null,
                    Instant.parse(expiresAt));
        }
        catch (Exception ex) {
            // 缓存内容损坏时不应继续使用不确定的凭证，要求重新授权。
            stringRedisTemplate.delete(TOKEN_REDIS_KEY);
            return null;
        }
    }

    private void verifyState(String state) {
        if (!StringUtils.hasText(state)) {
            throw new BusinessException("金山文档授权失败：缺少 state");
        }
        Instant expiresAt = pendingStates.remove(state);
        if (expiresAt == null || !expiresAt.isAfter(Instant.now())) {
            throw new BusinessException("金山文档授权失败：state 无效或已过期，请重新发起授权");
        }
    }

    private void verifyConfigured() {
        if (!StringUtils.hasText(properties.getAppId()) || !StringUtils.hasText(properties.getAppKey())
                || !StringUtils.hasText(properties.getRedirectUri())) {
            throw new BusinessException("请配置 KDOCS_APP_ID、KDOCS_APP_KEY 与 KDOCS_OAUTH_REDIRECT_URI");
        }
    }

    private String createState() {
        byte[] bytes = new byte[24];
        secureRandom.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private record OAuthToken(String accessToken, String refreshToken, Instant expiresAt) {
    }

    public record TokenStatus(boolean authorized, Instant expiresAt, boolean refreshTokenAvailable) {
    }
}
