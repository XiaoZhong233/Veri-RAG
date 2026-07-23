package com.example.verirag.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * 启动时确保上传根目录存在。
 */
@Configuration
@Slf4j
public class UploadBootstrapConfig {

    /**
     * 若 `file.upload.path` 对应目录不存在则创建。
     */
    @Bean
    ApplicationRunner ensureUploadDir(@Value("${file.upload.path}") String uploadPath) {
        return args -> {
            Path p = Paths.get(uploadPath);
            if (!Files.isDirectory(p)) {
                Files.createDirectories(p);
                log.info("已创建上传目录: {}", p.toAbsolutePath());
            }
        };
    }
}
