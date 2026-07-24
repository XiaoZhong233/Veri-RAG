package com.example.verirag.dto;

import java.util.List;

public record RoomOfferImportResult(
        Long batchId,
        int inventoryTotal,
        int inventoryInserted,
        int inventoryUpdated,
        int priceTotal,
        int priceInserted,
        int priceUpdated,
        int skipped,
        List<String> warnings
) {
}
