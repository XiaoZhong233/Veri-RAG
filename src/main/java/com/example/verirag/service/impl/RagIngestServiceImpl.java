package com.example.verirag.service.impl;

import com.example.verirag.service.RagIngestService;
import com.example.verirag.util.ExcelDocumentReader;
import com.example.verirag.util.ResidenceHtmlDocumentReader;
import com.example.verirag.util.ResidenceMarkdownDocumentReader;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.reader.markdown.MarkdownDocumentReader;
import org.springframework.ai.reader.markdown.config.MarkdownDocumentReaderConfig;
import org.springframework.ai.reader.tika.TikaDocumentReader;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.filter.Filter;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.ai.vectorstore.redis.RedisVectorStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.stereotype.Service;
import redis.clients.jedis.UnifiedJedis;
import redis.clients.jedis.json.Path2;
import redis.clients.jedis.params.ScanParams;
import redis.clients.jedis.resps.ScanResult;
import redis.clients.jedis.search.Query;
import redis.clients.jedis.search.SearchResult;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class RagIngestServiceImpl implements RagIngestService {

    private static final int REDIS_DELETE_BATCH_LIMIT = 10_000;
    private static final int PROVIDER_MAX_EMBEDDING_BATCH_SIZE = 20;

    private final RedisVectorStore redisVectorStore;
    private final TokenTextSplitter tokenTextSplitter;
    private final ExcelDocumentReader excelDocumentReader;
    private final ResidenceHtmlDocumentReader residenceHtmlDocumentReader;
    private final ResidenceMarkdownDocumentReader residenceMarkdownDocumentReader;
    @Value("${spring.ai.vectorstore.redis.index-name:spring-ai-index}")
    private String redisVectorIndexName;
    @Value("${spring.ai.vectorstore.redis.prefix:embedding:}")
    private String redisKeyPrefix;
    //删除向量时 SCAN 的 key 前缀列表（逗号分隔）。默认空则自动包含当前 prefix + 历史常用 embedding:
    @Value("${spring.ai.vectorstore.redis.delete-scan-prefixes:}")
    private String deleteScanPrefixesCsv;
    @Value("${rag.ingest.embedding-batch-size:20}")
    private int embeddingBatchSize;

    @Override
    public int ingest(Path absolutePath, String ext, Long documentId, Long categoryId, String title) {
        List<Document> loaded = loadDocuments(absolutePath, ext);
        List<Document> chunks = tokenTextSplitter.apply(loaded);
        List<Document> toAdd = new ArrayList<>();
        for (Document ch : chunks) {
            Map<String, Object> meta = new HashMap<>(ch.getMetadata());
            meta.put("docId", String.valueOf(documentId));
            meta.put("categoryId", String.valueOf(categoryId));
            meta.put("title", title != null ? title : "");
            String text = ch.getText();
            if (text == null || text.isBlank()) {
                continue;
            }
            text = contextualizeResidenceChunkForStorage(text, meta);
            toAdd.add(new Document(text, meta));
        }
        if (!toAdd.isEmpty()) {
            addInEmbeddingBatches(toAdd, documentId);
        }
        log.info("文档 {} 已向量化入库，块数 {}", documentId, toAdd.size());
        return toAdd.size();
    }

    /**
     * RedisVectorStore only returns metadata fields declared in its search schema.
     * Put the stable residence identity into the embedded text as well, so retrieval
     * and de-duplication remain correct even when custom metadata is not returned.
     */
    static String contextualizeResidenceChunkForStorage(
            String text, Map<String, Object> metadata) {
        String strippedText = text == null ? "" : text.strip();
        String residenceName = metadataText(metadata, "residenceName");
        if (residenceName == null || strippedText.startsWith("公寓名称：")) {
            return strippedText;
        }

        StringBuilder prefix = new StringBuilder()
                .append("公寓名称：").append(residenceName).append('\n');
        appendStorageMetadata(prefix, "区域", metadataText(metadata, "region"));
        appendStorageMetadata(prefix, "交通分区", metadataText(metadata, "zone"));
        appendStorageMetadata(prefix, "地址", metadataText(metadata, "address"));
        appendStorageMetadata(prefix, "最近车站", metadataText(metadata, "station"));
        return prefix.append("\n").append(strippedText).toString();
    }

    private static void appendStorageMetadata(
            StringBuilder target, String label, String value) {
        if (value != null) {
            target.append(label).append('：').append(value).append('\n');
        }
    }

    private static String metadataText(Map<String, Object> metadata, String key) {
        if (metadata == null || metadata.get(key) == null) {
            return null;
        }
        String value = String.valueOf(metadata.get(key)).strip();
        return value.isEmpty() ? null : value;
    }

    /**
     * 当前 Embedding 服务单次最多接受 20 条文本。Spring AI 默认按 Token 数拆批，
     * 对大量短房型记录可能仍产生超过 20 条的请求，因此在 VectorStore 之前增加条数限制。
     */
    private void addInEmbeddingBatches(List<Document> documents, Long documentId) {
        if (embeddingBatchSize > PROVIDER_MAX_EMBEDDING_BATCH_SIZE) {
            log.warn("Embedding batch size {} exceeds provider limit {}; using {}",
                    embeddingBatchSize, PROVIDER_MAX_EMBEDDING_BATCH_SIZE,
                    PROVIDER_MAX_EMBEDDING_BATCH_SIZE);
        }

        List<List<Document>> batches = partitionEmbeddingBatches(documents, embeddingBatchSize);
        for (int batchIndex = 0; batchIndex < batches.size(); batchIndex++) {
            List<Document> batch = batches.get(batchIndex);
            log.debug("文档 {} 提交 Embedding 批次 {}/{}，条数 {}",
                    documentId, batchIndex + 1, batches.size(), batch.size());
            redisVectorStore.add(batch);
        }
    }

    static List<List<Document>> partitionEmbeddingBatches(List<Document> documents,
                                                           int configuredSize) {
        int requestedSize = configuredSize <= 0
                ? PROVIDER_MAX_EMBEDDING_BATCH_SIZE
                : configuredSize;
        int batchSize = Math.min(requestedSize, PROVIDER_MAX_EMBEDDING_BATCH_SIZE);
        List<List<Document>> batches = new ArrayList<>();
        for (int from = 0; from < documents.size(); from += batchSize) {
            int to = Math.min(from + batchSize, documents.size());
            batches.add(new ArrayList<>(documents.subList(from, to)));
        }
        return batches;
    }

    /**
     * 删除embedding的文档向量
     * 框架过滤删除 + RediSearch 物理删除 + SCAN 兜底 + 残留校验
     * @param documentId 文档主键
     */
    @Override
    public void deleteVectorsByDocumentId(Long documentId) {
        if (documentId == null) {
            throw new IllegalArgumentException("Document id must not be null");
        }

        String docId = String.valueOf(documentId);
        String escapedTag = escapeRedisTag(docId);
        // Spring AI 2.0 暴露 RedisClient；它继承 UnifiedJedis，可执行 FT.SEARCH、JSON.*、SCAN 和 DEL。
        UnifiedJedis jedis = redisVectorStore.getJedisClient();
        List<String> scanPrefixes = resolveScanPrefixes();

        // 1. 优先使用 Spring AI 的标准 metadata filter 删除。
        deleteByFrameworkFilter(docId);

        // 2. RediSearch 查询命中的 chunk key 后，删除底层 JSON / Redis key。
        int deletedBySearch = deleteByRediSearch(jedis, scanPrefixes, escapedTag);

        // 3. 兼容历史 prefix 或索引异常的情况，扫描 JSON 中记录的 docId 兜底删除。
        int deletedByScan = deleteByScan(jedis, scanPrefixes, docId);

        if (anyChunkRemainingInIndex(jedis, escapedTag)) {
            log.error("文档 {} 删除后 Redis 索引仍存在向量块；index={}, prefixes={}",
                    documentId, redisVectorIndexName, scanPrefixes);
        }
        else {
            log.info("文档 {} 向量删除完成：RediSearch={}, SCAN={}",
                    documentId, deletedBySearch, deletedByScan);
        }

    }

    private void deleteByFrameworkFilter(String docId) {
        try {
            Filter.Expression expression = new FilterExpressionBuilder().eq("docId", docId).build();
            redisVectorStore.delete(expression);
        }
        catch (Exception ex) {
            // 失败后仍会走 FT.SEARCH 与 SCAN 的物理删除，不中断业务删除流程。
            log.debug("RedisVectorStore filter delete failed, docId={}: {}", docId, ex.toString());
        }
    }

    private List<String> resolveScanPrefixes() {
        Set<String> prefixes = new LinkedHashSet<>();
        if (org.springframework.util.StringUtils.hasText(deleteScanPrefixesCsv)) {
            Arrays.stream(deleteScanPrefixesCsv.split(","))
                    .map(String::trim)
                    .filter(org.springframework.util.StringUtils::hasText)
                    .map(RagIngestServiceImpl::ensureColonSuffix)
                    .forEach(prefixes::add);
        }
        prefixes.add(ensureColonSuffix(redisKeyPrefix));
        prefixes.add("embedding:");
        return new ArrayList<>(prefixes);
    }

    private static String ensureColonSuffix(String prefix) {
        if (prefix == null || prefix.isEmpty()) {
            return prefix;
        }
        return prefix.endsWith(":") ? prefix : prefix + ":";
    }

    private int deleteByRediSearch(UnifiedJedis jedis, List<String> prefixes, String escapedTag) {
        // 不同 Redis Search 版本对前导 * 的解析存在差异，因此依次尝试两种语法。
        String[] queries = {"* @docId:{" + escapedTag + "}", "@docId:{" + escapedTag + "}"};
        for (String queryString : queries) {
            int deleted = deleteByRediSearchQuery(jedis, prefixes, queryString);
            if (deleted > 0) {
                return deleted;
            }
        }
        return 0;
    }

    private int deleteByRediSearchQuery(UnifiedJedis jedis, List<String> prefixes, String queryString) {
        Query query = new Query(queryString).limit(0, REDIS_DELETE_BATCH_LIMIT).dialect(2);
        int deleted = 0;
        while (true) {
            SearchResult result;
            try {
                result = jedis.ftSearch(redisVectorIndexName, query);
            }
            catch (Exception ex) {
                log.debug("FT.SEARCH failed, query={}: {}", queryString, ex.toString());
                return deleted;
            }

            List<redis.clients.jedis.search.Document> documents = result.getDocuments();
            if (documents == null || documents.isEmpty()) {
                return deleted;
            }

            int batchDeleted = 0;
            for (redis.clients.jedis.search.Document document : documents) {
                if (unlinkKeyCandidates(jedis, prefixes, document.getId())) {
                    batchDeleted++;
                }
            }
            deleted += batchDeleted;

            if (batchDeleted == 0) {
                log.warn("FT.SEARCH 命中 {} 条记录，但没有成功删除任何 Redis key；停止以避免死循环", documents.size());
                return deleted;
            }
            if (documents.size() < REDIS_DELETE_BATCH_LIMIT) {
                return deleted;
            }
        }
    }

    private static boolean unlinkKeyCandidates(UnifiedJedis jedis, List<String> prefixes, String keyFromSearch) {
        if (keyFromSearch == null || keyFromSearch.isBlank()) {
            return false;
        }
        Set<String> candidates = new LinkedHashSet<>();
        candidates.add(keyFromSearch);
        String suffix = stripKnownPrefix(keyFromSearch, prefixes);
        for (String prefix : prefixes) {
            candidates.add(prefix + suffix);
        }
        for (String key : candidates) {
            if (unlinkJsonOrKey(jedis, key)) {
                return true;
            }
        }
        return false;
    }

    private static String stripKnownPrefix(String key, List<String> prefixes) {
        for (String prefix : prefixes) {
            if (key.startsWith(prefix)) {
                return key.substring(prefix.length());
            }
        }
        return key;
    }

    private static boolean unlinkJsonOrKey(UnifiedJedis jedis, String key) {
        try {
            Long deleted = jedis.jsonDel(key);
            if (deleted != null && deleted > 0) {
                return true;
            }
        }
        catch (Exception ignored) {
            // 非 JSON key 或当前 Redis 未启用 JSON 模块时，继续尝试 DEL。
        }
        try {
            Long deleted = jedis.del(key);
            return deleted != null && deleted > 0;
        }
        catch (Exception ex) {
            return false;
        }
    }

    private int deleteByScan(UnifiedJedis jedis, List<String> prefixes, String docId) {
        int deleted = 0;
        for (String prefix : prefixes) {
            String cursor = "0";
            ScanParams params = new ScanParams().match(prefix + "*").count(500);
            do {
                ScanResult<String> scanResult = jedis.scan(cursor, params);
                for (String key : scanResult.getResult()) {
                    if (docId.equals(readStoredDocId(jedis, key)) && unlinkJsonOrKey(jedis, key)) {
                        deleted++;
                    }
                }
                cursor = scanResult.getCursor();
            } while (!"0".equals(cursor));
        }
        return deleted;
    }

    private static String readStoredDocId(UnifiedJedis jedis, String key) {
        try {
            Object value = jedis.jsonGet(key, new Path2("$.docId"));
            String docId = normalizeJsonValue(value);
            if (docId != null) {
                return docId;
            }
            return normalizeJsonValue(jedis.jsonGet(key, new Path2("$.metadata.docId")));
        }
        catch (Exception ex) {
            return null;
        }
    }

    private static String normalizeJsonValue(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof List<?> values) {
            return values.isEmpty() ? null : normalizeJsonValue(values.getFirst());
        }
        String text = String.valueOf(value).trim();
        if (text.startsWith("[") && text.endsWith("]")) {
            text = text.substring(1, text.length() - 1).trim();
        }
        if (text.length() >= 2 && text.startsWith("\"") && text.endsWith("\"")) {
            text = text.substring(1, text.length() - 1);
        }
        return text;
    }

    private boolean anyChunkRemainingInIndex(UnifiedJedis jedis, String escapedTag) {
        try {
            Query query = new Query("* @docId:{" + escapedTag + "}").limit(0, 1).dialect(2);
            SearchResult result = jedis.ftSearch(redisVectorIndexName, query);
            return result.getDocuments() != null && !result.getDocuments().isEmpty();
        }
        catch (Exception ex) {
            log.warn("无法校验 Redis 索引是否仍存在残留向量：{}", ex.toString());
            return false;
        }

    }

    private List<Document> loadDocuments(Path absolutePath, String ext) {
        if (absolutePath == null || !Files.isRegularFile(absolutePath)) {
            throw new IllegalArgumentException("Document file does not exist");
        }

        String normalizedExt = ext == null ? "" : ext.trim()
                .replaceFirst("^\\.", "")
                .toLowerCase(Locale.ROOT);

        FileSystemResource resource = new FileSystemResource(absolutePath);

        return switch (normalizedExt) {
            case "md", "markdown" -> residenceMarkdownDocumentReader.supports(absolutePath)
                    ? residenceMarkdownDocumentReader.read(absolutePath)
                    : new MarkdownDocumentReader(
                            resource,
                            MarkdownDocumentReaderConfig.defaultConfig()
                    ).get();
            case "pdf", "doc", "docx", "txt", "text" ->
                    new TikaDocumentReader(resource).get();
            case "xlsx" -> excelDocumentReader.read(absolutePath);
            case "html", "htm" -> residenceHtmlDocumentReader.read(absolutePath);
            default -> throw new IllegalArgumentException(
                    "Unsupported document type: " + normalizedExt
            );
        };
    }

    //转义符号处理
    private static String escapeRedisTag(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Redis tag value must not be blank");
        }

        StringBuilder sb = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '\\', '$', '|', '{', '}', '(', ')', '[', ']', '-', '\'' ->
                        sb.append('\\').append(c);
                default -> sb.append(c);
            }
        }
        return sb.toString();
    }

}
