package com.example.verirag.dto;

import java.math.BigDecimal;

public record ResidenceNearbyPlaceView(
        Long id,
        String placeType,
        String placeName,
        String travelDescription,
        Integer minMinutes,
        Integer maxMinutes,
        String travelMode,
        BigDecimal distanceMiles,
        Integer sortOrder
) {
}
