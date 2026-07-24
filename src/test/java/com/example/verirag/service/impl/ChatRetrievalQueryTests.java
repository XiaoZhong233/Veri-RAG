package com.example.verirag.service.impl;

import com.example.verirag.entity.ChatMessage;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;

import java.io.InterruptedIOException;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ChatRetrievalQueryTests {

    @Test
    void keepsAvailabilityQuestionFocusedOnRoomOffers() {
        String query = ChatServiceImpl.buildRetrievalQuery(
                "LSE附近9月份有哪些公寓可以预定", List.of());

        assertThat(query).isEqualTo("LSE附近9月份有哪些公寓可以预定");
    }

    @Test
    void doesNotApplyLegacyLocationAndRoomOfferRewrite() {
        String query = ChatServiceImpl.buildRetrievalQuery(
                "帮我找UCL附近9月起租，租期为6个月的公寓", List.of());

        assertThat(query).isEqualTo("帮我找UCL附近9月起租，租期为6个月的公寓");
    }

    @Test
    void keepsStaticResidenceKnowledgeButDropsLegacyRoomOfferChunks() {
        Document residence = new Document(
                "## Highbury Residence\n\n- 地址：309 Holloway Road",
                Map.of("residenceId", "highbury"));
        Document legacyOffer = new Document(
                "## Chapter Highbury\n\n### Bronze Ensuite\n\n- 库存：充裕",
                Map.of("propertyName", "Chapter Highbury", "roomType", "Bronze Ensuite"));

        assertThat(ChatServiceImpl.keepStaticPropertyKnowledge(
                List.of(residence, legacyOffer)))
                .containsExactly(residence);
    }

    @Test
    void keepsOneBestChunkPerResidenceForLocationCandidates() {
        Document islingtonUniversity = new Document(
                "UCL: 18 minutes via tube",
                Map.of("residenceName", "Islington Residence"));
        Document islingtonStation = new Document(
                "Caledonian Road station",
                Map.of("residenceName", "Islington Residence"));
        Document highbury = new Document(
                "UCL: 18 minutes via tube",
                Map.of("residenceName", "Highbury Residence"));
        Document kingsCross = new Document(
                "UCL: 10 minutes via tube",
                Map.of("residenceName", "King's Cross Residence"));

        assertThat(ChatServiceImpl.distinctResidenceKnowledge(
                List.of(islingtonUniversity, islingtonStation, highbury, kingsCross), 2))
                .containsExactly(islingtonUniversity, highbury);
    }

    @Test
    void deduplicatesResidenceCandidatesUsingStoredTextIdentity() {
        Document highburyUniversity = new Document(
                "公寓名称：Highbury Residence\n\nUCL: 18 minutes");
        Document highburyStation = new Document(
                "公寓名称：Highbury Residence\n\nHolloway Road");
        Document islington = new Document(
                "公寓名称：Islington Residence\n\nUCL: 18 minutes");

        assertThat(ChatServiceImpl.distinctResidenceKnowledge(
                List.of(highburyUniversity, highburyStation, islington), 12))
                .containsExactly(highburyUniversity, islington);
    }

    @Test
    void preservesConversationContextForCountFollowUp() {
        ChatMessage previous = new ChatMessage();
        previous.setRole("USER");
        previous.setContent("只看伦敦地区");

        String query = ChatServiceImpl.buildRetrievalQuery(
                "一共有几个公寓？", List.of(previous));

        assertThat(query).isEqualTo("只看伦敦地区\n后续问题：一共有几个公寓？");
    }

    @Test
    void reportsTimeoutAsRecoverablePartialStreamError() {
        RuntimeException streamFailure = new RuntimeException(
                "Stream failed", new InterruptedIOException("timeout"));

        assertThat(ChatServiceImpl.friendlyStreamError(streamFailure))
                .isEqualTo("模型响应超时，已保留当前生成内容，请重试。");
    }

}
