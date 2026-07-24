package com.example.verirag.service.impl;

import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;

import java.util.List;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

class RagIngestServiceImplTests {

    @Test
    void sendsAtMostTwentyDocumentsPerEmbeddingRequest() {
        List<Document> rooms = IntStream.range(0, 45)
                .mapToObj(index -> new Document("## 公寓\n\n### 房型" + index))
                .toList();

        List<List<Document>> batches =
                RagIngestServiceImpl.partitionEmbeddingBatches(rooms, 20);

        assertThat(batches)
                .extracting(List::size)
                .containsExactly(20, 20, 5);
        assertThat(batches)
                .allSatisfy(batch -> assertThat(batch).hasSizeLessThanOrEqualTo(20));
        assertThat(batches).flatExtracting(batch -> batch).containsExactlyElementsOf(rooms);
    }

    @Test
    void capsMisconfiguredBatchSizeAtProviderLimit() {
        List<Document> rooms = IntStream.range(0, 21)
                .mapToObj(index -> new Document("房型" + index))
                .toList();

        List<List<Document>> batches =
                RagIngestServiceImpl.partitionEmbeddingBatches(rooms, 100);

        assertThat(batches)
                .extracting(List::size)
                .containsExactly(20, 1);
    }
}
