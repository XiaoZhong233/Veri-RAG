package com.example.verirag.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record RoomPriceTierRequest(
        @NotNull(message = "最短租期不能为空")
        @Min(value = 1, message = "最短租期至少为1周")
        @Max(value = 104, message = "最短租期不能超过104周")
        Integer minWeeks,

        @Min(value = 1, message = "最长租期至少为1周")
        @Max(value = 104, message = "最长租期不能超过104周")
        Integer maxWeeks,

        @NotNull(message = "每周价格不能为空")
        @DecimalMin(value = "0.01", message = "每周价格必须大于0")
        BigDecimal weeklyPrice,

        @NotBlank(message = "币种不能为空")
        @Size(max = 3, message = "币种必须为3位代码")
        String currency,

        @NotNull(message = "价格更新时间不能为空")
        LocalDateTime priceUpdatedAt,

        @Size(max = 1024, message = "价格备注不能超过1024个字符")
        String note
) {
}
