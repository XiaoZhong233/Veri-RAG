package com.example.verirag.service.impl;

import com.example.verirag.common.FileTypeUtil;
import com.example.verirag.common.PageResult;
import com.example.verirag.common.ResultCode;
import com.example.verirag.entity.Document;
import com.example.verirag.exception.BusinessException;
import com.example.verirag.mapper.DocumentMapper;
import com.example.verirag.service.DocumentService;
import com.example.verirag.service.FileStorageService;
import com.example.verirag.service.RagIngestService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

@Slf4j
@Service
@RequiredArgsConstructor
public class DocumentServiceImpl implements DocumentService {

    @Value("${file.upload.path}")
    private String uploadRoot;
    private final DocumentMapper documentMapper;
    private final FileStorageService fileStorageService;
    private final RagIngestService ragIngestService;

    @Override
    public Document upload(MultipartFile file, Long categoryId, String title, Long uploadUserId) throws Exception {
        if (!FileTypeUtil.allowed(file)) {
            throw new BusinessException("Only TXT, PDF, DOC, DOCX, and Markdown files are supported");
        }

        String originalFileName = file.getOriginalFilename();
        String extension = FileTypeUtil.ext(originalFileName);
        FileStorageService.StoredFile storedFile = fileStorageService.save(file);

        Document document = new Document();
        document.setCategoryId(categoryId);
        document.setTitle(title != null && !title.isBlank() ? title.trim() : originalFileName);
        document.setFileName(originalFileName);
        document.setFilePath(storedFile.relativePath());
        document.setFileType(extension);
        document.setFileSize(file.getSize());
        document.setStatus("PROCESSING");
        document.setVectorCount(0);
        document.setUploadUserId(uploadUserId);
        documentMapper.insert(document);

        try {
            int chunkCount = ragIngestService.ingest(
                    storedFile.absolutePath(), extension, document.getId(), categoryId, document.getTitle());
            document.setVectorCount(chunkCount);
            document.setStatus("SUCCESS");
            documentMapper.updateById(document);
        }
        catch (Exception ex) {
            document.setStatus("FAIL");
            documentMapper.updateById(document);
            log.error("Document {} ingestion failed", document.getId(), ex);
            throw ex;
        }

        return documentMapper.selectById(document.getId());
    }

    @Override
    public PageResult<Document> page(String keyword, Long categoryId, int page, int size) {
        int safePage = Math.max(page, 1);
        int safeSize = Math.min(Math.max(size, 1), 100);
        String query = keyword == null ? "" : keyword.trim();

        LambdaQueryWrapper<Document> wrapper = new LambdaQueryWrapper<Document>()
                .eq(categoryId != null, Document::getCategoryId, categoryId)
                .orderByDesc(Document::getId);
        if (!query.isEmpty()) {
            wrapper.and(condition -> condition
                    .like(Document::getTitle, query)
                    .or()
                    .like(Document::getFileName, query));
        }

        Page<Document> result = documentMapper.selectPage(new Page<>(safePage, safeSize), wrapper);
        return PageResult.of(result.getTotal(), result.getRecords());
    }

    @Override
    public void delete(Long id) throws Exception {
        if (id == null) {
            throw new BusinessException(ResultCode.BAD_REQUEST.getCode(), "Document id must not be null");
        }

        Document document = documentMapper.selectById(id);
        if (document == null) {
            throw new BusinessException(ResultCode.NOT_FOUND.getCode(), "Document not found");
        }

        // 若 Redis 向量删除失败，直接中止，以保留数据库记录和原文件供后续重试。
        ragIngestService.deleteVectorsByDocumentId(id);
        documentMapper.deleteById(id);
        deleteOriginalFile(document.getFilePath());

    }

    /**
     * 只允许删除 uploads 根目录内的文件，防止相对路径穿越误删其它路径。
     */
    private void deleteOriginalFile(String relativePath) {
        if (relativePath == null || relativePath.isBlank()) {
            return;
        }

        Path root = Paths.get(uploadRoot).toAbsolutePath().normalize();
        Path file = root.resolve(relativePath).normalize();
        if (!file.startsWith(root) || file.equals(root) || Files.isDirectory(file)) {
            log.warn("Skip unsafe document file deletion: {}", relativePath);
            return;
        }
        try {
            if (Files.deleteIfExists(file)) {
                log.info("Deleted original document file: {}", file);
            }
        }
        catch (IOException ex) {
            // 数据库与向量库已清理，文件清理失败只记录告警，避免向客户端返回伪失败。
            log.warn("Document record was removed but original file could not be deleted: {}", file, ex);
        }
    }
}
