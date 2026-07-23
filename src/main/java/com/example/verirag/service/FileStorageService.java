package com.example.verirag.service;

import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Path;

public interface FileStorageService {
    /**
     * 已保存文件描述。
     *
     * @param relativePath 相对 uploads 根的路径
     * @param absolutePath 绝对路径
     */
    record StoredFile(String relativePath, Path absolutePath) {}

    /**
     * 保存上传文件并返回的路径
     *
     * @param file 上传文件
     * @return 相对路径与磁盘绝对路径
     * @throws IOException IO 异常
     */
    StoredFile save(MultipartFile file) throws IOException;
}
