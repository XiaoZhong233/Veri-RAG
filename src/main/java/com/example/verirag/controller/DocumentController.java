package com.example.verirag.controller;

import com.example.verirag.common.PageResult;
import com.example.verirag.common.R;
import com.example.verirag.dto.BatchReingestRequest;
import com.example.verirag.dto.BatchReingestResult;
import com.example.verirag.entity.Document;
import com.example.verirag.service.DocumentService;
import com.example.verirag.util.SecurityUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import jakarta.validation.Valid;

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

    /** 供聊天引用详情查看；文件本身仍通过 /files/** 访问。 */
    @GetMapping("/{id}")
    public R<Document> detail(@PathVariable Long id) {
        return R.ok(documentService.getById(id));
    }

    /**
     * 基于磁盘中已保存的原文件重建当前文档的向量。
     */
    @PreAuthorize("hasRole('ADMIN')")
    @PostMapping("/{id}/reingest")
    public R<Document> reingest(@PathVariable Long id) throws Exception {
        return R.ok(documentService.reingest(id));
    }

    /**
     * 批量基于磁盘原文件重建向量；某个文件失败不影响其余文件。
     */
    @PreAuthorize("hasRole('ADMIN')")
    @PostMapping("/reingest")
    public R<BatchReingestResult> reingestBatch(@Valid @RequestBody BatchReingestRequest request) {
        return R.ok(documentService.reingestBatch(request.getDocumentIds()));
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
