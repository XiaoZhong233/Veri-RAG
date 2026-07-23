package com.example.verirag.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 登录成功返回：JWT 与用户信息。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class LoginResponse {
    private String token;
    private UserBrief user;

    /**
     * 登录用户简要信息
     */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class UserBrief {
        private Long id;
        private String username;
        private String realName;
        private String role;
        /** 头像相对路径 */
        private String avatar;
    }
}
