package com.example.verirag.service;

import java.nio.file.Path;

public interface RagIngestService {
    /**
     * 将本地文件解析并向量化写入 Redis VectorStore。
     *
     * @param absolutePath 磁盘绝对路径
     * @param ext            不含点的扩展名小写
     * @param documentId     业务文档主键，写入 metadata.docId 便于删除
     * @param categoryId     分类 ID
     * @param title          展示标题
     * @return 写入的向量块数量
     */
    int ingest(Path absolutePath, String ext, Long documentId, Long categoryId, String title);

    /**
     * 按业务文档 ID 删除向量库中全部切片。
     *
     * @param documentId 文档主键
     */
    void deleteVectorsByDocumentId(Long documentId);
}
