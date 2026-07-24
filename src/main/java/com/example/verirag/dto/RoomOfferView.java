package com.example.verirag.dto;

import com.example.verirag.entity.RoomPriceTier;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

public record RoomOfferView(
        Long id,
        Long residenceId,
        String residenceSourceId,
        String residenceName,
        String roomCode,
        String roomName,
        String rootType,
        LocalDate earliestStartDate,
        LocalDate latestEndDate,
        Integer remainingQuantity,
        String inventoryStatus,
        LocalDateTime inventoryUpdatedAt,
        String note,
        String sourceFileName,
        LocalDateTime createTime,
        LocalDateTime updateTime,
        List<RoomPriceTier> priceTiers
) {
}
