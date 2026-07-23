package com.example.verirag.util;

import org.springframework.ai.document.Document;
import org.springframework.ai.document.DocumentReader;
import org.springframework.ai.reader.markdown.MarkdownDocumentReader;
import org.springframework.ai.reader.tika.TikaDocumentReader;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

import java.io.File;
import java.util.List;
import java.util.Locale;

@Component
public class DocumentParseUtil {

    public List<Document> parse(String filePath){
        File file = new File(filePath);
        String suffix = filePath.substring(filePath.lastIndexOf('.')+1).toLowerCase(Locale.ROOT);
        Resource fileSystemResource = new FileSystemResource(file);
        DocumentReader reader = switch (suffix){
            case "pdf", "doc", "docx", "txt", "text" -> new TikaDocumentReader(fileSystemResource);
            case "md", "markdown" -> new MarkdownDocumentReader(file.toURI().toString());
            default -> throw new IllegalArgumentException("Unsupported file format!: " + suffix);
        };
        return reader.get();
    }
}
