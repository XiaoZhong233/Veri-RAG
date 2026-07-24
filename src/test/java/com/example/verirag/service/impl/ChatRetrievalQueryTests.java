package com.example.verirag.service.impl;

import com.example.verirag.entity.ChatMessage;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;

import java.io.InterruptedIOException;
import java.time.LocalDate;
import java.util.Map;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ChatRetrievalQueryTests {

    @Test
    void expandsApartmentCountQuestionTowardPortfolioSummary() {
        String query = ChatServiceImpl.buildRetrievalQuery(
                "你们在伦敦有多少个公寓", List.of());

        assertThat(query)
                .contains("你们在伦敦有多少个公寓")
                .contains("【全量公寓统计检索】")
                .contains("Londonist 伦敦公寓位置总览", "完整公寓名单");
    }

    @Test
    void keepsAvailabilityQuestionFocusedOnRoomOffers() {
        String query = ChatServiceImpl.buildRetrievalQuery(
                "LSE附近9月份有哪些公寓可以预定", List.of());

        assertThat(query).isEqualTo("LSE附近9月份有哪些公寓可以预定");
    }

    @Test
    void expandsUclQuestionToMainCampusAndExcludesUclEast() {
        String query = ChatServiceImpl.buildRetrievalQuery(
                "帮我找UCL附近9月起租，租期为6个月的公寓", List.of());

        assertThat(query)
                .contains("【学校位置检索】")
                .contains("University College London (UCL) 主校区")
                .contains("排除 UCL East");
    }

    @Test
    void doesNotRewriteExplicitUclEastQuestion() {
        String query = ChatServiceImpl.buildRetrievalQuery(
                "UCL East附近9月有哪些公寓", List.of());

        assertThat(query).isEqualTo("UCL East附近9月有哪些公寓");
    }

    @Test
    void preservesConversationContextForCountFollowUp() {
        ChatMessage previous = new ChatMessage();
        previous.setRole("USER");
        previous.setContent("只看伦敦地区");

        String query = ChatServiceImpl.buildRetrievalQuery(
                "一共有几个公寓？", List.of(previous));

        assertThat(query)
                .startsWith("只看伦敦地区\n后续问题：一共有几个公寓？")
                .contains("【全量公寓统计检索】");
    }

    @Test
    void normalizesResidenceAliasesAcrossHtmlAndPriceList() {
        assertThat(ChatServiceImpl.canonicalResidenceName("Highbury Residence"))
                .isEqualTo(ChatServiceImpl.canonicalResidenceName(
                        "Chapter Highbury（2026.8.29--2027.8.23)"));
        assertThat(ChatServiceImpl.canonicalResidenceName("Islington Residence"))
                .isEqualTo(ChatServiceImpl.canonicalResidenceName(
                        "Chapter Islington（2026.8.29--2027.8.24)"));
        assertThat(ChatServiceImpl.canonicalResidenceName("King’s Cross Residence"))
                .isEqualTo(ChatServiceImpl.canonicalResidenceName(
                        "Chapter Kingscross（2026.8.29--2027.8.24)"));
        assertThat(ChatServiceImpl.canonicalResidenceName("Venti House"))
                .isEqualTo(ChatServiceImpl.canonicalResidenceName("Fresh Venti House"));
    }

    @Test
    void keepsNumberedResidencesDistinct() {
        assertThat(ChatServiceImpl.canonicalResidenceName("Highbury Residence"))
                .isNotEqualTo(ChatServiceImpl.canonicalResidenceName("Chapter Highbury II"));
        assertThat(ChatServiceImpl.canonicalResidenceName("Highbury II Residence"))
                .isEqualTo(ChatServiceImpl.canonicalResidenceName("Chapter Highbury II"));
    }

    @Test
    void reportsTimeoutAsRecoverablePartialStreamError() {
        RuntimeException streamFailure = new RuntimeException(
                "Stream failed", new InterruptedIOException("timeout"));

        assertThat(ChatServiceImpl.friendlyStreamError(streamFailure))
                .isEqualTo("模型响应超时，已保留当前生成内容，请重试。");
    }

    @Test
    void identifiesAndRanksExplicitUclMainCampusLocation() {
        Document highbury = new Document("""
                ## Highbury Residence
                - **区域**: 北伦敦
                - **地址**: 309 Holloway Road
                ### 附近大学
                - University College London (UCL)：15 min by tube
                """);
        Document uclEast = new Document("""
                ## Venti House
                - **区域**: 东伦敦
                - **地址**: 1 Example Road
                ### 附近大学
                - UCL East：5 min walk
                """);

        assertThat(ChatServiceImpl.isUclMainLocationDocument(highbury)).isTrue();
        assertThat(ChatServiceImpl.uclCommuteMinutes(highbury)).isEqualTo(15);
        assertThat(ChatServiceImpl.isUclMainLocationDocument(uclEast)).isFalse();
    }

    @Test
    void keepsSoldOutOfferButFiltersDurationAndStartMonth() {
        Document available = new Document("""
                ## Chapter Highbury（2026.8.29--2027.8.23)
                ### Gold Ensuite Standard View Mid Level
                - **20-39 Weeks**: 460
                - **Room Availability**: 充裕
                """);
        Document soldOut = new Document("""
                ## Chapter Highbury（2026.8.29--2027.8.23)
                ### Bronze Ensuite
                - **20-39 Weeks**: 450
                - **Room Availability**: 售罄
                """, Map.of("propertyName", "Chapter Highbury（2026.8.29--2027.8.23)"));

        String request = "帮我找UCL附近9月起租，租期为6个月的公寓";
        assertThat(ChatServiceImpl.matchesRoomRequest(available, request)).isTrue();
        assertThat(ChatServiceImpl.matchesRoomRequest(soldOut, request)).isTrue();
        assertThat(ChatServiceImpl.matchesRoomRequest(
                available, "帮我找UCL附近9月起租，租期为44周的公寓")).isFalse();
    }

    @Test
    void marksExpiredPromotionPriceAsReconfirmationRequired() {
        Document offer = new Document("""
                ## Prestige Paddington Citi View（2026.9.13--2027.9.5)
                ### Twin Bed Studio
                - **20-39 Weeks**: 470
                - **Room Availability**: 6个床位（3间）
                - **Note**: 限时特惠 截止到5.31日
                """);

        Document annotated = ChatServiceImpl.annotateExpiredPromotion(
                offer, LocalDate.of(2026, 7, 24));

        assertThat(annotated.getText())
                .contains("限时优惠已于 2026-05-31 过期")
                .contains("表中价格不能视为当前有效报价");
        assertThat(annotated.getMetadata()).containsEntry("promotionExpired", true);
    }
}
