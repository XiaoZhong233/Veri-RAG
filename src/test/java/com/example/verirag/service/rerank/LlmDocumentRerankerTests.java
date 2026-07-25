package com.example.verirag.service.rerank;

import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class LlmDocumentRerankerTests {

    @Test
    void ordersCandidatesByIndependentRerankerScore() {
        Document vectorFirst = new Document("generic policy", Map.of("title", "A"));
        Document answerBearing = new Document("the exact answer", Map.of("title", "B"));
        Document unrelated = new Document("unrelated", Map.of("title", "C"));

        List<Document> ranked = LlmDocumentReranker.rankByScores(
                List.of(vectorFirst, answerBearing, unrelated),
                Map.of(0, 0.55, 1, 0.98, 2, 0.10));

        assertThat(ranked).containsExactly(answerBearing, vectorFirst, unrelated);
    }
}
