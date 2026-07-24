package com.example.verirag.dto;

import java.util.List;

public record ResidenceDetailSourceData(
        String sourceId,
        String name,
        String officialId,
        String city,
        String address,
        String zone,
        String postcode,
        String station,
        String transportLines,
        String officialUrl,
        String pageTags,
        List<String> facilities,
        List<ResidenceNearbyPlaceSourceData> nearbyPlaces,
        String detailMarkdown
) {
}
