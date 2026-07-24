package com.example.verirag.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@TableName("t_residence_nearby_place")
public class ResidenceNearbyPlace {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long residenceId;
    private String placeType;
    private String placeName;
    private String travelDescription;
    private Integer minMinutes;
    private Integer maxMinutes;
    private String travelMode;
    private BigDecimal distanceMiles;
    private Integer sortOrder;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
