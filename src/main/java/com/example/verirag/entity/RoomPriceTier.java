package com.example.verirag.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@TableName("t_room_price_tier")
public class RoomPriceTier {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long inventoryId;
    private Integer minWeeks;
    private Integer maxWeeks;
    private BigDecimal weeklyPrice;
    private String currency;
    private LocalDateTime priceUpdatedAt;
    private String note;
    private String sourceFileName;
    private Long importBatchId;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
