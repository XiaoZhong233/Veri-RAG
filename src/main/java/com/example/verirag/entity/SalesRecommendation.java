package com.example.verirag.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("t_sales_recommendation")
public class SalesRecommendation {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long residenceId;
    private Integer priority;
    private Integer enabled;
    private String note;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
