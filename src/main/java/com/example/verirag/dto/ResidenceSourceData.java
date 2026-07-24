package com.example.verirag.dto;

/**
 * 从公寓地图 HTML 提取的结构化地址数据。
 */
public record ResidenceSourceData(
        String sourceId,
        String name,
        String region,
        String zone,
        String latitude,
        String longitude,
        String address,
        String station,
        String mapUrl,
        String sourceBlock
) {
}
