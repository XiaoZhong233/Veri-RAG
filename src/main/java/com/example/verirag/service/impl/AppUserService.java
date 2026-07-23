package com.example.verirag.service.impl;

import com.example.verirag.common.PageResult;
import com.example.verirag.dto.LoginRequest;
import com.example.verirag.dto.LoginResponse;
import com.example.verirag.dto.UserSaveRequest;
import com.example.verirag.entity.AppUser;
import org.springframework.web.multipart.MultipartFile;

public interface AppUserService {
    /**
     * 用户名密码登录，密码与库中 MD5 比对。
     */
    LoginResponse login(LoginRequest req);

    AppUser getById(Long id);

    PageResult<AppUser> page(String keyword, int page, int size);

    void save(UserSaveRequest req);

    void delete(Long id);

    void updateProfile(Long userId, String realName);

    /**
     * 上传并更新头像。
     */
    String updateAvatar(Long userId, MultipartFile file);

    void changePassword(Long userId, String oldPassword, String newPassword);
}
