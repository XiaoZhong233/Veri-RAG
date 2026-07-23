package com.example.verirag.service;

import com.example.verirag.common.PageResult;
import com.example.verirag.entity.Document;
import org.springframework.web.multipart.MultipartFile;

public interface DocumentService {

    Document upload(MultipartFile file, Long categoryId, String title, Long uploadUserId) throws Exception;

    PageResult<Document> page(String keyword, Long categoryId, int page, int size);

    void delete(Long id) throws Exception;
}
