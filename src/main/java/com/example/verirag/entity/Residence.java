package com.example.verirag.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@TableName("t_residence")
public class Residence {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String sourceId;
    private String name;
    private String city;
    private String region;
    private String zone;
    private String address;
    private String station;
    private BigDecimal latitude;
    private BigDecimal longitude;
    private String mapUrl;
    private String sourceFileName;
    private String sourceHash;
    private Integer active;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
