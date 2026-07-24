package com.example.verirag.dto;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * 公寓地址库概览。
 */
public record ResidenceStats(
        long total,
        Map<String, Long> cities,
        Map<String, Long> regions,
        LocalDateTime lastUpdated
) {
}
