package com.example.verirag.dto;

/**
 * HTML 公寓地址增量导入结果。
 */
public record ResidenceImportResult(
        int total,
        int inserted,
        int updated,
        int unchanged,
        int deactivated,
        String sourceFileName
) {
}
