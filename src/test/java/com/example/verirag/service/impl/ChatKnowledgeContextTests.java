package com.example.verirag.service.impl;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ChatKnowledgeContextTests {

    @Test
    void repeatsResidenceIdentityWhenTokenChunkLostItsMarkdownHeading() {
        String result = ChatServiceImpl.contextualizeKnowledgeChunk(
                "- University College London (UCL): 15 minutes via tube",
                Map.of(
                        "residenceName", "Islington Residence",
                        "region", "north",
                        "zone", "Zone 2",
                        "address", "London N7"));

        assertThat(result)
                .contains("公寓名称：Islington Residence")
                .contains("区域：north")
                .contains("交通分区：Zone 2")
                .contains("地址：London N7")
                .contains("University College London (UCL): 15 minutes via tube");
    }

    @Test
    void leavesNonResidenceKnowledgeUnchanged() {
        String result = ChatServiceImpl.contextualizeKnowledgeChunk(
                "普通制度文档内容",
                Map.of("title", "员工手册"));

        assertThat(result).isEqualTo("普通制度文档内容");
    }

    @Test
    void resolvesResidenceNameFromStoredChunkTextWithoutRedisMetadata() {
        var document = new org.springframework.ai.document.Document(
                "公寓名称：Highbury Residence\n\n"
                        + "- University College London (UCL): 18 minutes via tube",
                Map.of("title", "londonist-residences.md"));

        assertThat(ChatServiceImpl.resolveResidenceName(document))
                .isEqualTo("Highbury Residence");
    }

    @Test
    void resolvesResidenceNameFromMarkdownHeadingForOlderChunks() {
        var document = new org.springframework.ai.document.Document(
                "## Islington Residence\n\n- UCL: 18 minutes via tube",
                Map.of("title", "londonist-residences.md"));

        assertThat(ChatServiceImpl.resolveResidenceName(document))
                .isEqualTo("Islington Residence");
    }
}
