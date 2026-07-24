package com.example.verirag.dto;

import java.time.LocalDateTime;

public record SalesRecommendationView(
        Long id,
        Long residenceId,
        String residenceSourceId,
        String residenceName,
        String city,
        Integer priority,
        Integer enabled,
        String note,
        LocalDateTime createTime,
        LocalDateTime updateTime
) {
}
