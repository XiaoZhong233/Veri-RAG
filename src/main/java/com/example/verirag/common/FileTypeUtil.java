package com.example.verirag.common;

import org.springframework.web.multipart.MultipartFile;

import java.util.Locale;
import java.util.Set;

/**
 * 知识库允许上传的文件类型校验。
 */
public final class FileTypeUtil {

    /** 允许的扩展名（小写，不含点） */
    private static final Set<String> ALLOWED = Set.of(
            "txt", "pdf", "doc", "docx", "md", "xlsx", "html", "htm"
    );

    private FileTypeUtil() {
    }

    /**
     * 从原始文件名解析扩展名（小写、不含点），若无则返回空串。
     *
     * @param originalFilename 原始文件名
     * @return 扩展名
     */
    public static String ext(String originalFilename) {
        if (originalFilename == null || originalFilename.isBlank()) {
            return "";
        }
        int i = originalFilename.lastIndexOf('.');
        if (i < 0 || i == originalFilename.length() - 1) {
            return "";
        }
        return originalFilename.substring(i + 1).toLowerCase(Locale.ROOT);
    }

    /**
     * 是否允许上传该文件。
     *
     * @param file 上传文件
     * @return true 允许
     */
    public static boolean allowed(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            return false;
        }
        return ALLOWED.contains(ext(file.getOriginalFilename()));
    }
}
