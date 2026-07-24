package com.example.verirag.service;

import com.example.verirag.common.PageResult;
import com.example.verirag.dto.BatchReingestResult;
import com.example.verirag.entity.Document;
import org.springframework.web.multipart.MultipartFile;

public interface DocumentService {

    Document upload(MultipartFile file, Long categoryId, String title, Long uploadUserId) throws Exception;

    PageResult<Document> page(String keyword, Long categoryId, int page, int size);

    Document getById(Long id);

    /** 删除旧向量并基于已保存的原文件重新分块、向量化。 */
    Document reingest(Long id) throws Exception;

    /**
     * 顺序重建多个文档的向量。单个文档失败不会中断其余文档，具体结果见返回值。
     */
    BatchReingestResult reingestBatch(java.util.List<Long> documentIds);

    void delete(Long id) throws Exception;
}
