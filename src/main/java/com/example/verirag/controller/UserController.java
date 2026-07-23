package com.example.verirag.controller;

import com.example.verirag.common.PageResult;
import com.example.verirag.common.R;
import com.example.verirag.dto.ProfileUpdateRequest;
import com.example.verirag.dto.UserSaveRequest;
import com.example.verirag.entity.AppUser;
import com.example.verirag.exception.BusinessException;
import com.example.verirag.service.AppUserService;
import com.example.verirag.util.SecurityUtils;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

/**
 * 用户管理（管理员 CRUD）与个人中心。
 */
@RestController
@RequestMapping("/api/users")
@RequiredArgsConstructor
public class UserController {

    private final AppUserService appUserService;

    /**
     * 分页查询用户（管理员）。
     */
    @PreAuthorize("hasRole('ADMIN')")
    @GetMapping("/page")
    public R<PageResult<AppUser>> page(@RequestParam(defaultValue = "") String keyword,
                                       @RequestParam(defaultValue = "1") int page,
                                       @RequestParam(defaultValue = "10") int size) {
        return R.ok(appUserService.page(keyword, page, size));
    }

    /**
     * 新增或更新用户（管理员）。
     */
    @PreAuthorize("hasRole('ADMIN')")
    @PostMapping
    public R<Void> save(@Valid @RequestBody UserSaveRequest req) {
        appUserService.save(req);
        return R.ok();
    }

    /**
     * 删除用户（管理员）。
     */
    @PreAuthorize("hasRole('ADMIN')")
    @DeleteMapping("/{id}")
    public R<Void> delete(@PathVariable Long id) {
        appUserService.delete(id);
        return R.ok();
    }

    /**
     * 当前登录用户详情。
     */
    @GetMapping("/me")
    public R<AppUser> me() {
        var u = SecurityUtils.requireUser();
        AppUser full = appUserService.getById(u.getUserId());
        if (full != null) {
            full.setPassword(null);
        }
        return R.ok(full);
    }

    /**
     * 更新个人资料。
     */
    @PutMapping("/me/profile")
    public R<Void> profile(@RequestBody ProfileUpdateRequest body) {
        var u = SecurityUtils.requireUser();
        appUserService.updateProfile(u.getUserId(), body.getRealName());
        return R.ok();
    }

    /**
     * 上传头像（管理员与普通用户均可）。
     */
    @PostMapping(value = "/me/avatar", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public R<String> uploadAvatar(@RequestPart("file") MultipartFile file) {
        var u = SecurityUtils.requireUser();
        String relative = appUserService.updateAvatar(u.getUserId(), file);
        return R.ok(relative);
    }

    /**
     * 修改本人密码。
     */
    @PutMapping("/me/password")
    public R<Void> password(@RequestParam String oldPassword, @RequestParam String newPassword) {
        if (newPassword == null || newPassword.length() < 6) {
            throw new BusinessException("新密码至少 6 位");
        }
        var u = SecurityUtils.requireUser();
        appUserService.changePassword(u.getUserId(), oldPassword, newPassword);
        return R.ok();
    }
}
