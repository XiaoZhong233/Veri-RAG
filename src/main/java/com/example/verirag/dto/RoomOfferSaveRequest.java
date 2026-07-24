package com.example.verirag.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

public record RoomOfferSaveRequest(
        Long id,

        @NotNull(message = "请选择公寓")
        Long residenceId,

        @NotBlank(message = "房型编码不能为空")
        @Size(max = 128, message = "房型编码不能超过128个字符")
        String roomCode,

        @NotBlank(message = "房型名称不能为空")
        @Size(max = 255, message = "房型名称不能超过255个字符")
        String roomName,

        @NotBlank(message = "Root Type不能为空")
        @Size(max = 32, message = "Root Type不能超过32个字符")
        String rootType,

        @NotNull(message = "最早起租日期不能为空")
        LocalDate earliestStartDate,

        @NotNull(message = "最晚退房日期不能为空")
        LocalDate latestEndDate,

        @Min(value = 0, message = "剩余数量不能小于0")
        @Max(value = 100000, message = "剩余数量过大")
        Integer remainingQuantity,

        @NotBlank(message = "库存状态不能为空")
        String inventoryStatus,

        @NotNull(message = "库存更新时间不能为空")
        LocalDateTime inventoryUpdatedAt,

        @Size(max = 1024, message = "库存备注不能超过1024个字符")
        String note,

        @NotEmpty(message = "至少填写一个价格档位")
        List<@Valid RoomPriceTierRequest> priceTiers
) {
}
