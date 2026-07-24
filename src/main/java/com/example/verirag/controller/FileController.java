package com.example.verirag.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.example.verirag.entity.Document;
import com.example.verirag.mapper.DocumentMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.MediaTypeFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * 仅提供非知识库文件（当前为头像）的静态访问。
 * 已入库的原始知识文档不会经由此端点暴露或下载。
 */
@RestController
@RequiredArgsConstructor
public class FileController {

    private final DocumentMapper documentMapper;

    @Value("${file.upload.path}")
    private String uploadRoot;

    @GetMapping("/files/{folder}/{filename:.+}")
    public ResponseEntity<Resource> getFile(@PathVariable String folder, @PathVariable String filename) {
        String relativePath = folder + "/" + filename;
        Long documentCount = documentMapper.selectCount(new LambdaQueryWrapper<Document>()
                .eq(Document::getFilePath, relativePath));
        if (documentCount != null && documentCount > 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }

        Path root = Paths.get(uploadRoot).toAbsolutePath().normalize();
        Path file = root.resolve(relativePath).normalize();
        if (!file.startsWith(root) || !Files.isRegularFile(file)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        Resource resource = new FileSystemResource(file);
        MediaType contentType = MediaTypeFactory.getMediaType(resource).orElse(MediaType.APPLICATION_OCTET_STREAM);
        return ResponseEntity.ok().contentType(contentType).body(resource);
    }
}
