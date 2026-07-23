package com.example.verirag.service.impl;

import com.example.verirag.common.JwtUtil;
import com.example.verirag.common.PageResult;
import com.example.verirag.dto.LoginRequest;
import com.example.verirag.dto.LoginResponse;
import com.example.verirag.dto.UserSaveRequest;
import com.example.verirag.entity.AppUser;
import com.example.verirag.mapper.AppUserMapper;
import com.github.pagehelper.PageHelper;
import com.github.pagehelper.PageInfo;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.DigestUtils;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import javax.imageio.ImageIO;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.YearMonth;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class AppUserServiceImpl implements AppUserService {

    private static final int MAX_PAGE_SIZE = 100;
    private static final long MAX_AVATAR_SIZE = 5 * 1024 * 1024;
    private static final Set<String> ALLOWED_ROLES = Set.of("ADMIN", "USER");
    private static final Set<String> ALLOWED_AVATAR_EXTENSIONS = Set.of("jpg", "jpeg", "png", "gif");

    private final AppUserMapper appUserMapper;
    private final JwtUtil jwtUtil;

    @Value("${file.upload.path:./file}")
    private String uploadRoot;

    @Override
    public LoginResponse login(LoginRequest req) {
        String username = required(req.getUsername(), "Username must not be blank");
        AppUser user = appUserMapper.findByUsername(username);
        if (user == null || !md5(req.getPassword()).equalsIgnoreCase(user.getPassword())) {
            throw new IllegalArgumentException("Invalid username or password");
        }
        if (!Integer.valueOf(1).equals(user.getStatus())) {
            throw new IllegalStateException("This account has been disabled");
        }

        String token = jwtUtil.createToken(user.getId(), user.getUsername(), user.getRole());
        return new LoginResponse(token, new LoginResponse.UserBrief(
                user.getId(), user.getUsername(), user.getRealName(), user.getRole(), user.getAvatar()));
    }

    @Override
    public AppUser getById(Long id) {
        return id == null ? null : appUserMapper.findById(id);
    }

    @Override
    public PageResult<AppUser> page(String keyword, int page, int size) {
        int safePage = Math.max(page, 1);
        int safeSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
        String query = keyword == null ? "" : keyword.trim();
        PageHelper.startPage(safePage, safeSize);
        List<AppUser> records = appUserMapper.findPage(query);
        PageInfo<AppUser> pageInfo = new PageInfo<>(records);
        return PageResult.of(pageInfo.getTotal(), pageInfo.getList());
    }

    @Override
    public void save(UserSaveRequest req) {
        String username = required(req.getUsername(), "Username must not be blank");
        String role = required(req.getRole(), "Role must not be blank").toUpperCase(Locale.ROOT);
        if (!ALLOWED_ROLES.contains(role)) {
            throw new IllegalArgumentException("Role must be ADMIN or USER");
        }
        int status = req.getStatus() == null ? 1 : req.getStatus();
        if (status != 0 && status != 1) {
            throw new IllegalArgumentException("Status must be 0 or 1");
        }

        AppUser sameName = appUserMapper.findByUsername(username);
        if (sameName != null && !sameName.getId().equals(req.getId())) {
            throw new IllegalArgumentException("Username already exists");
        }

        AppUser user = new AppUser();
        user.setId(req.getId());
        user.setUsername(username);
        user.setRealName(trimToNull(req.getRealName()));
        user.setRole(role);
        user.setStatus(status);
        if (req.getId() == null) {
            user.setPassword(md5(required(req.getPassword(), "Password must not be blank")));
            appUserMapper.insert(user);
            return;
        }

        requireExisting(req.getId());
        appUserMapper.update(user);
        if (StringUtils.hasText(req.getPassword())) {
            appUserMapper.updatePassword(req.getId(), md5(req.getPassword()));
        }
    }

    @Override
    public void delete(Long id) {
        requireExisting(id);
        appUserMapper.deleteById(id);
    }

    @Override
    public void updateProfile(Long userId, String realName) {
        requireExisting(userId);
        appUserMapper.updateProfile(userId, trimToNull(realName));
    }

    @Override
    public String updateAvatar(Long userId, MultipartFile file) {
        requireExisting(userId);
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("Avatar file must not be empty");
        }
        if (file.getSize() > MAX_AVATAR_SIZE) {
            throw new IllegalArgumentException("Avatar must not exceed 5 MB");
        }

        String extension = extension(file.getOriginalFilename());
        if (!ALLOWED_AVATAR_EXTENSIONS.contains(extension)) {
            throw new IllegalArgumentException("Only JPG, PNG, and GIF avatars are supported");
        }
        try (InputStream input = file.getInputStream()) {
            if (ImageIO.read(input) == null) {
                throw new IllegalArgumentException("Avatar file is not a valid image");
            }
        } catch (IOException e) {
            throw new IllegalStateException("Failed to validate avatar file", e);
        }

        String relativePath = "avatars/" + YearMonth.now() + "/" + UUID.randomUUID() + "." + extension;
        Path target = Paths.get(uploadRoot).resolve(relativePath).normalize();
        Path root = Paths.get(uploadRoot).toAbsolutePath().normalize();
        if (!target.toAbsolutePath().normalize().startsWith(root)) {
            throw new IllegalArgumentException("Invalid avatar path");
        }
        try {
            Files.createDirectories(target.getParent());
            file.transferTo(target);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to store avatar file", e);
        }
        appUserMapper.updateAvatar(userId, relativePath);
        return relativePath;
    }

    @Override
    public void changePassword(Long userId, String oldPassword, String newPassword) {
        AppUser user = requireExisting(userId);
        if (!md5(oldPassword).equalsIgnoreCase(user.getPassword())) {
            throw new IllegalArgumentException("Current password is incorrect");
        }
        appUserMapper.updatePassword(userId, md5(required(newPassword, "New password must not be blank")));
    }

    private AppUser requireExisting(Long id) {
        if (id == null) {
            throw new IllegalArgumentException("User id must not be null");
        }
        AppUser user = appUserMapper.findById(id);
        if (user == null) {
            throw new IllegalArgumentException("User not found");
        }
        return user;
    }

    private String md5(String value) {
        return DigestUtils.md5DigestAsHex(required(value, "Password must not be blank").getBytes());
    }

    private String required(String value, String message) {
        if (!StringUtils.hasText(value)) {
            throw new IllegalArgumentException(message);
        }
        return value.trim();
    }

    private String trimToNull(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }

    private String extension(String fileName) {
        if (!StringUtils.hasText(fileName)) {
            return "";
        }
        int dot = fileName.lastIndexOf('.');
        return dot < 0 ? "" : fileName.substring(dot + 1).toLowerCase(Locale.ROOT);
    }
}
