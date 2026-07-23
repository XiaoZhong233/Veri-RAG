package com.example.verirag.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.UUID;

/**
 * 文件保存服务
 */
@Service
@Slf4j
public class FileStorageServiceImpl implements FileStorageService{

    private static final DateTimeFormatter YM = DateTimeFormatter.ofPattern("yyyyMM", Locale.ROOT);

    @Value("${file.upload.path}")
    private String uploadRoot;

    @Override
    public StoredFile save(MultipartFile file) throws IOException {
        String ym = LocalDate.now().format(YM);
        String original = file.getOriginalFilename() != null ? file.getOriginalFilename() : "file";
        int dot = original.lastIndexOf('.');
        String ext = dot > 0 ? original.substring(dot) : "";
        String name = UUID.randomUUID().toString().replace("-", "") + ext.toLowerCase(Locale.ROOT);
        Path dir = Paths.get(uploadRoot, ym);
        Files.createDirectories(dir);
        Path target = dir.resolve(name);
        file.transferTo(target);
        String relative = ym + "/" + name;
        return new StoredFile(relative, target);
    }
}
