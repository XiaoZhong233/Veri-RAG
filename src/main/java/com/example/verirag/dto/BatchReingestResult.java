package com.example.verirag.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.util.List;

/** 批量重新向量化的逐文档处理结果。 */
@Data
@AllArgsConstructor
public class BatchReingestResult {

    private int requestedCount;
    private int successCount;
    private int failedCount;
    private List<Item> items;

    @Data
    @AllArgsConstructor
    public static class Item {
        private Long documentId;
        private String title;
        private boolean success;
        private Integer vectorCount;
        private String error;
    }
}
