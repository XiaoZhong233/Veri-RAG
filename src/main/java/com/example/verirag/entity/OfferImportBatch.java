package com.example.verirag.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("t_offer_import_batch")
public class OfferImportBatch {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String fileName;
    private String fileHash;
    private String status;
    private Integer inventoryTotal;
    private Integer inventoryInserted;
    private Integer inventoryUpdated;
    private Integer priceTotal;
    private Integer priceInserted;
    private Integer priceUpdated;
    private Integer skipped;
    private Long uploadUserId;
    private String message;
    private LocalDateTime createTime;
    private LocalDateTime finishTime;
}
