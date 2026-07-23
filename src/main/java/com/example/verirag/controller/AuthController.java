package com.example.verirag.controller;

import com.example.verirag.common.R;
import com.example.verirag.dto.LoginRequest;
import com.example.verirag.dto.LoginResponse;
import com.example.verirag.service.AppUserService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 认证：登录（JWT）。
 */
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AppUserService appUserService;
    /**
     * 用户名密码登录，返回 Token。
     */
    @PostMapping("/login")
    public R<LoginResponse> login(@Valid @RequestBody LoginRequest req) {
        LoginResponse resp = appUserService.login(req);
        return R.ok(resp);
    }
}
