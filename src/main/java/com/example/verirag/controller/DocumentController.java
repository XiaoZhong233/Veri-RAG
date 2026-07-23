package com.example.verirag.controller;

import com.example.verirag.common.PageResult;
import com.example.verirag.common.R;
import com.example.verirag.entity.Document;
import com.example.verirag.service.DocumentService;
import com.example.verirag.util.SecurityUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

/**
 * 知识文档上传与管理。
 */
@RestController
@RequestMapping("/api/documents")
@RequiredArgsConstructor
public class DocumentController {

    private final DocumentService documentService;

    /**
     * 上传并向量化（管理员）。
     */
    @PreAuthorize("hasRole('ADMIN')")
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public R<Document> upload(@RequestPart("file") MultipartFile file,
                              @RequestParam Long categoryId,
                              @RequestParam(required = false) String title) throws Exception {
        var u = SecurityUtils.requireUser();
        return R.ok(documentService.upload(file, categoryId, title, u.getUserId()));
    }

    /**
     * 文档分页（管理员）。
     */
    @PreAuthorize("hasRole('ADMIN')")
    @GetMapping("/page")
    public R<PageResult<Document>> page(@RequestParam(defaultValue = "") String keyword,
                                        @RequestParam(required = false) Long categoryId,
                                        @RequestParam(defaultValue = "1") int page,
                                        @RequestParam(defaultValue = "10") int size) {
        return R.ok(documentService.page(keyword, categoryId, page, size));
    }

    /**
     * 删除文档及向量（管理员）。
     */
    @PreAuthorize("hasRole('ADMIN')")
    @DeleteMapping("/{id}")
    public R<Void> delete(@PathVariable Long id) throws Exception {
        documentService.delete(id);
        return R.ok();
    }
}
