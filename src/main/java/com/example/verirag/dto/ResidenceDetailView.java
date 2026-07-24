package com.example.verirag.dto;

import java.time.LocalDateTime;
import java.util.List;

public record ResidenceDetailView(
        Long residenceId,
        String residenceSourceId,
        String residenceName,
        String city,
        String officialId,
        String postcode,
        String transportLines,
        String officialUrl,
        String pageTags,
        List<String> facilities,
        List<ResidenceNearbyPlaceView> nearbyPlaces,
        String sourceFileName,
        LocalDateTime detailUpdatedAt,
        LocalDateTime updateTime
) {
}
