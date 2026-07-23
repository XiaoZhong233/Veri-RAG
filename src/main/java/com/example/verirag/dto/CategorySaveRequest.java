package com.example.verirag.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 分类保存请求。
 */
@Data
public class CategorySaveRequest {
    private Long id;
    @NotBlank(message = "分类名称不能为空")
    private String name;
    private String description;
    private String icon;
    private Integer sortOrder = 0;
}
