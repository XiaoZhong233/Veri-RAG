package com.example.verirag.common;

import cn.hutool.json.JSONObject;
import cn.hutool.jwt.JWT;
import cn.hutool.jwt.JWTUtil;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * JWT 生成与解析工具。
 */
@Component
public class JwtUtil {

    @Value("${jwt.secret}")
    private String secret;

    @Value("${jwt.expire-hours:24}")
    private long expireHours;

    /**
     * 解析后的载荷，供过滤器使用。
     *
     * @param userId   用户 ID
     * @param username 登录名
     * @param role     角色
     */
    public record ParsedToken(Long userId, String username, String role) {
    }

    /**
     * 根据用户主键、登录名、角色签发 Token。
     *
     * @param userId   用户 ID
     * @param username 登录名
     * @param role     角色
     * @return JWT 字符串
     */
    public String createToken(Long userId, String username, String role) {
        long nowSec = System.currentTimeMillis() / 1000;
        Map<String, Object> payload = new HashMap<>();
        payload.put("uid", userId);
        payload.put("username", username);
        payload.put("role", role);
        payload.put("sub", username);
        payload.put("iat", nowSec);
        payload.put("exp", nowSec + expireHours * 3600);
        return JWTUtil.createToken(payload, secret.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 解析并校验 Token，失败返回 null。
     *
     * @param token Bearer 后的完整 token
     */
    public ParsedToken parse(String token) {
        try {
            JWT jwt = JWTUtil.parseToken(token);
            boolean ok = jwt.setKey(secret.getBytes(StandardCharsets.UTF_8)).verify();
            if (!ok) {
                return null;
            }
            JSONObject pl = jwt.getPayloads();
            if (pl == null) {
                return null;
            }
            Long expiration = pl.getLong("exp");
            long nowSec = System.currentTimeMillis() / 1000;
            if (expiration == null || expiration <= nowSec) {
                return null;
            }
            Long uid = pl.getLong("uid");
            String username = pl.getStr("username");
            if (username == null || username.isEmpty()) {
                username = pl.getStr("sub");
            }
            String r = pl.getStr("role");
            return new ParsedToken(uid, username, r);
        } catch (Exception e) {
            return null;
        }
    }
}
