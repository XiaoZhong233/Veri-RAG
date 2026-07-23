package com.example.verirag.util;

import jakarta.servlet.http.HttpServletRequest;

/**
 * Web 请求工具（如客户端 IP）。
 */
public final class WebUtils {

    private WebUtils() {
    }

    /**
     * 尽力获取真实客户端 IP（考虑反向代理常见头）。
     */
    public static String clientIp(HttpServletRequest request) {
        String h = request.getHeader("X-Forwarded-For");
        if (h != null && !h.isBlank()) {
            return h.split(",")[0].trim();
        }
        h = request.getHeader("X-Real-IP");
        if (h != null && !h.isBlank()) {
            return h.trim();
        }
        return request.getRemoteAddr();
    }
}
