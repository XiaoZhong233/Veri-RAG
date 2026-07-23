package com.example.verirag.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 用户实体，对应表 t_user。
 */
@Data
public class AppUser {
    private Long id;
    private String username;
    @JsonIgnore
    private String password;
    private String realName;
    /** 头像相对路径，如 202605/xxx.png，访问 /files/该路径 */
    private String avatar;
    /** ADMIN / USER */
    private String role;
    private Integer status;
    private LocalDateTime createTime;
}
