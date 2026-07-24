package com.example.verirag.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record SalesRecommendationSaveRequest(
        Long id,

        @NotNull(message = "请选择推荐公寓")
        Long residenceId,

        @NotNull(message = "请填写推荐优先级")
        @Min(value = 1, message = "推荐优先级不能小于1")
        @Max(value = 999, message = "推荐优先级不能大于999")
        Integer priority,

        Integer enabled,

        @Size(max = 512, message = "推荐备注不能超过512个字符")
        String note
) {
}
