package com.example.verirag.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

public record ResidenceDetailSaveRequest(
        @NotNull(message = "公寓ID不能为空")
        Long residenceId,

        @Size(max = 64, message = "官网公寓ID不能超过64个字符")
        String officialId,

        @Size(max = 32, message = "邮编不能超过32个字符")
        String postcode,

        @Size(max = 512, message = "交通线路不能超过512个字符")
        String transportLines,

        @Size(max = 1024, message = "官网地址不能超过1024个字符")
        String officialUrl,

        @Size(max = 512, message = "页面标签不能超过512个字符")
        String pageTags,

        List<@Size(max = 255, message = "设施名称不能超过255个字符") String> facilities,

        List<@Valid ResidenceNearbyPlaceRequest> nearbyPlaces
) {
}
