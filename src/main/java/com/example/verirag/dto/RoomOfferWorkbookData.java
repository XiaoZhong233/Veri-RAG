package com.example.verirag.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

public record RoomOfferWorkbookData(
        List<InventoryRow> inventories,
        List<PriceRow> prices
) {
    public record InventoryRow(
            int rowNumber,
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
            String note
    ) {
    }

    public record PriceRow(
            int rowNumber,
            String residenceSourceId,
            String residenceName,
            String roomCode,
            String roomName,
            LocalDate earliestStartDate,
            LocalDate latestEndDate,
            Integer minWeeks,
            Integer maxWeeks,
            BigDecimal weeklyPrice,
            String currency,
            LocalDateTime priceUpdatedAt,
            String note
    ) {
    }
}
