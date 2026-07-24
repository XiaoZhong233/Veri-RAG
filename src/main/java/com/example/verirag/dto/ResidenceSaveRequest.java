package com.example.verirag.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

public record ResidenceSaveRequest(
        Long id,

        @NotBlank(message = "公寓编码不能为空")
        @Size(max = 128, message = "公寓编码不能超过128个字符")
        String sourceId,

        @NotBlank(message = "公寓名称不能为空")
        @Size(max = 255, message = "公寓名称不能超过255个字符")
        String name,

        @NotBlank(message = "城市不能为空")
        @Size(max = 128, message = "城市不能超过128个字符")
        String city,

        @Size(max = 32, message = "区域不能超过32个字符")
        String region,

        @Size(max = 64, message = "Zone不能超过64个字符")
        String zone,

        @NotBlank(message = "完整地址不能为空")
        @Size(max = 512, message = "地址不能超过512个字符")
        String address,

        @Size(max = 255, message = "最近车站不能超过255个字符")
        String station,

        BigDecimal latitude,
        BigDecimal longitude,

        @Size(max = 1024, message = "地图链接不能超过1024个字符")
        String mapUrl,

        Integer active
) {
}
