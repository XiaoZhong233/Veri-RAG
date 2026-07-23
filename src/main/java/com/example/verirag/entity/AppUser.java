package com.example.verirag.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 用户实体，对应表 t_user。
 */
@Data
@TableName("t_user")
public class AppUser {
    @TableId(type = IdType.AUTO)
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
