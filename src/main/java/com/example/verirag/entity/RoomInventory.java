package com.example.verirag.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
@TableName("t_room_inventory")
public class RoomInventory {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long residenceId;
    private String roomCode;
    private String roomName;
    private String rootType;
    private LocalDate earliestStartDate;
    private LocalDate latestEndDate;
    private Integer remainingQuantity;
    private String inventoryStatus;
    private LocalDateTime inventoryUpdatedAt;
    private String note;
    private String sourceFileName;
    private Long importBatchId;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
