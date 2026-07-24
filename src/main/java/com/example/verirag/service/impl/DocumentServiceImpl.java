package com.example.verirag.service.impl;

import com.example.verirag.common.FileTypeUtil;
import com.example.verirag.common.PageResult;
import com.example.verirag.common.ResultCode;
import com.example.verirag.dto.BatchReingestResult;
import com.example.verirag.entity.Document;
import com.example.verirag.exception.BusinessException;
import com.example.verirag.mapper.DocumentMapper;
import com.example.verirag.mapper.CategoryMapper;
import com.example.verirag.service.DocumentService;
import com.example.verirag.service.FileStorageService;
import com.example.verirag.service.RagIngestService;
import com.example.verirag.service.RagAnswerCache;
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
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class DocumentServiceImpl implements DocumentService {

    @Value("${file.upload.path}")
    private String uploadRoot;
    private final DocumentMapper documentMapper;
    private final CategoryMapper categoryMapper;
    private final FileStorageService fileStorageService;
    private final RagIngestService ragIngestService;
    private final RagAnswerCache ragAnswerCache;

    @Override
    public Document upload(MultipartFile file, Long categoryId, String title, Long uploadUserId) throws Exception {
        if (!FileTypeUtil.allowed(file)) {
            throw new BusinessException(
                    "Only TXT, PDF, DOC, DOCX, Markdown, XLSX, and HTML files are supported");
        }
        if (categoryId == null || categoryMapper.selectById(categoryId) == null) {
            throw new BusinessException(ResultCode.NOT_FOUND.getCode(), "Category not found");
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
            ragAnswerCache.evictAll();
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
    public Document getById(Long id) {
        if (id == null) {
            throw new BusinessException(ResultCode.BAD_REQUEST.getCode(), "Document id must not be null");
        }
        Document document = documentMapper.selectById(id);
        if (document == null) {
            throw new BusinessException(ResultCode.NOT_FOUND.getCode(), "Document not found");
        }
        return document;
    }

    @Override
    public Document reingest(Long id) throws Exception {
        if (id == null) {
            throw new BusinessException(ResultCode.BAD_REQUEST.getCode(), "Document id must not be null");
        }
        Document document = documentMapper.selectById(id);
        if (document == null) {
            throw new BusinessException(ResultCode.NOT_FOUND.getCode(), "Document not found");
        }

        Path sourceFile = resolveOriginalFile(document.getFilePath());
        if (!Files.isRegularFile(sourceFile)) {
            throw new BusinessException(ResultCode.NOT_FOUND.getCode(), "Original document file not found");
        }

        document.setStatus("PROCESSING");
        document.setVectorCount(0);
        documentMapper.updateById(document);
        try {
            ragIngestService.deleteVectorsByDocumentId(id);
            String extension = document.getFileType();
            if (extension == null || extension.isBlank()) {
                extension = FileTypeUtil.ext(document.getFileName());
            }
            int chunkCount = ragIngestService.ingest(
                    sourceFile, extension, id, document.getCategoryId(), document.getTitle());
            document.setVectorCount(chunkCount);
            document.setStatus("SUCCESS");
            documentMapper.updateById(document);
            ragAnswerCache.evictAll();
            return documentMapper.selectById(id);
        }
        catch (Exception ex) {
            document.setStatus("FAIL");
            documentMapper.updateById(document);
            log.error("Document {} re-ingestion failed", id, ex);
            throw ex;
        }
    }

    @Override
    public BatchReingestResult reingestBatch(List<Long> documentIds) {
        if (documentIds == null || documentIds.isEmpty()) {
            throw new BusinessException(ResultCode.BAD_REQUEST.getCode(), "Please select at least one document");
        }

        List<Long> ids = documentIds.stream()
                .filter(java.util.Objects::nonNull)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new))
                .stream()
                .toList();
        if (ids.isEmpty()) {
            throw new BusinessException(ResultCode.BAD_REQUEST.getCode(), "Please select at least one document");
        }
        if (ids.size() > 50) {
            throw new BusinessException(ResultCode.BAD_REQUEST.getCode(), "A maximum of 50 documents can be re-vectorized at once");
        }

        List<BatchReingestResult.Item> items = new ArrayList<>(ids.size());
        int successCount = 0;
        for (Long id : ids) {
            Document before = documentMapper.selectById(id);
            String title = before == null ? null : before.getTitle();
            try {
                Document rebuilt = reingest(id);
                items.add(new BatchReingestResult.Item(id, rebuilt.getTitle(), true,
                        rebuilt.getVectorCount(), null));
                successCount++;
            }
            catch (Exception ex) {
                log.error("Document {} batch re-ingestion failed", id, ex);
                items.add(new BatchReingestResult.Item(id, title, false, null, safeErrorMessage(ex)));
            }
        }
        return new BatchReingestResult(ids.size(), successCount, ids.size() - successCount, items);
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
        ragAnswerCache.evictAll();
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

    private Path resolveOriginalFile(String relativePath) {
        if (relativePath == null || relativePath.isBlank()) {
            throw new BusinessException(ResultCode.NOT_FOUND.getCode(), "Original document file not found");
        }
        Path root = Paths.get(uploadRoot).toAbsolutePath().normalize();
        Path file = root.resolve(relativePath).normalize();
        if (!file.startsWith(root) || file.equals(root) || Files.isDirectory(file)) {
            throw new BusinessException(ResultCode.BAD_REQUEST.getCode(), "Invalid stored document path");
        }
        return file;
    }

    private String safeErrorMessage(Exception ex) {
        String message = ex.getMessage();
        return message == null || message.isBlank() ? "Re-vectorization failed" : message;
    }
}
