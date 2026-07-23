package com.example.verirag.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.verirag.entity.AppUser;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface AppUserMapper extends BaseMapper<AppUser> {

    AppUser findByUsername(@Param("username") String username);
}
