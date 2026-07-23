package com.example.verirag.util;

import com.example.verirag.security.LoginUserDetails;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * 从 SecurityContext 获取当前登录用户。
 */
public final class SecurityUtils {

    private SecurityUtils() {
    }

    /**
     * 当前登录用户，未登录返回 null。
     */
    public static LoginUserDetails currentUser() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !(auth.getPrincipal() instanceof LoginUserDetails u)) {
            return null;
        }
        return u;
    }

    /**
     * 必须已登录，否则抛 IllegalStateException。
     */
    public static LoginUserDetails requireUser() {
        LoginUserDetails u = currentUser();
        if (u == null) {
            throw new IllegalStateException("未登录");
        }
        return u;
    }
}
