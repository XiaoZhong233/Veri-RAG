package com.example.verirag.dto;

public record RoomOfferStats(
        long total,
        long available,
        long limited,
        long soldOut,
        long unknown
) {
}
