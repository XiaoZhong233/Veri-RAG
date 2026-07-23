package com.example.verirag.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 新增/编辑用户请求。
 */
@Data
public class UserSaveRequest {
    private Long id;
    @NotBlank(message = "用户名不能为空")
    private String username;
    /** 为空表示不修改密码（编辑时） */
    private String password;
    private String realName;
    @NotBlank(message = "角色不能为空")
    private String role;
    private Integer status = 1;
}
