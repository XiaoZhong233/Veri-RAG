package com.example.verirag.mapper;

import com.example.verirag.entity.AppUser;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface AppUserMapper {

    AppUser findById(@Param("id") Long id);

    AppUser findByUsername(@Param("username") String username);

    List<AppUser> findPage(@Param("keyword") String keyword);

    int insert(AppUser user);

    int update(AppUser user);

    int updatePassword(@Param("id") Long id, @Param("password") String password);

    int updateProfile(@Param("id") Long id, @Param("realName") String realName);

    int updateAvatar(@Param("id") Long id, @Param("avatar") String avatar);

    int deleteById(@Param("id") Long id);
}
