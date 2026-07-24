package com.example.verirag.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record ResidenceNearbyPlaceRequest(
        Long id,

        @NotBlank(message = "附近地点类型不能为空")
        @Pattern(regexp = "UNIVERSITY|LANDMARK", message = "附近地点类型无效")
        String placeType,

        @NotBlank(message = "附近地点名称不能为空")
        @Size(max = 255, message = "附近地点名称不能超过255个字符")
        String placeName,

        @Size(max = 512, message = "通勤描述不能超过512个字符")
        String travelDescription,

        Integer sortOrder
) {
}
