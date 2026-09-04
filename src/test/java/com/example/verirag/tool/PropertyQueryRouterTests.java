package com.example.verirag.tool;

import com.example.verirag.entity.ChatMessage;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PropertyQueryRouterTests {

    @Test
    void routesStructuredPropertyQuestions() {
        assertThat(PropertyQueryRouter.isStructuredPropertyQuery(
                "帮我找伦敦9月起租、租期26周的公寓", List.of())).isTrue();
        assertThat(PropertyQueryRouter.isStructuredPropertyQuery(
                "Manchester有哪些可以预订的房源？", List.of())).isTrue();
        assertThat(PropertyQueryRouter.isStructuredPropertyQuery(
                "帮我找UCL附近9月起租，租期为6个月的公寓", List.of())).isTrue();
        assertThat(PropertyQueryRouter.isStructuredPropertyQuery(
                "请总结上传文档的主要内容", List.of())).isFalse();
    }

    @Test
    void selectsOneToolIntentForEachPropertyQuestion() {
        assertThat(PropertyQueryRouter.route(
                "帮我找UCL附近9月起租，租期为6个月的公寓", List.of()))
                .isEqualTo(PropertyQueryIntent.RECOMMEND);
        assertThat(PropertyQueryRouter.route(
                "伦敦有哪些公寓？", List.of()))
                .isEqualTo(PropertyQueryIntent.LIST);
        assertThat(PropertyQueryRouter.route(
                "你们在伦敦有多少个公寓？", List.of()))
                .isEqualTo(PropertyQueryIntent.SUMMARY);
        assertThat(PropertyQueryRouter.route(
                "伦敦有多少公寓？", List.of()))
                .isEqualTo(PropertyQueryIntent.SUMMARY);
        assertThat(PropertyQueryRouter.route(
                "伦敦有哪些公寓可以预订？", List.of()))
                .isEqualTo(PropertyQueryIntent.RECOMMEND);
        assertThat(PropertyQueryRouter.route(
                "roomOfferId 123住26周总价多少？", List.of()))
                .isEqualTo(PropertyQueryIntent.QUOTE);
        assertThat(PropertyQueryRouter.route(
                "Drapery Place 有哪些设施和附近学校？", List.of()))
                .isEqualTo(PropertyQueryIntent.DETAIL);
        assertThat(PropertyQueryRouter.route(
                "Drapery Place Residence 附近有什么地标？", List.of()))
                .isEqualTo(PropertyQueryIntent.DETAIL);
        assertThat(PropertyQueryRouter.route(
                "这个房型住26周总价多少？", List.of()))
                .isEqualTo(PropertyQueryIntent.RECOMMEND);
        assertThat(PropertyQueryRouter.route(
                "总结一下这份制度", List.of()))
                .isEqualTo(PropertyQueryIntent.NONE);
    }

    @Test
    void routesShortFollowUpFromPropertyHistory() {
        ChatMessage previous = new ChatMessage();
        previous.setRole("USER");
        previous.setContent("伦敦有哪些公寓？");

        assertThat(PropertyQueryRouter.isStructuredPropertyQuery(
                "那曼彻斯特呢？", List.of(previous))).isTrue();
        assertThat(PropertyQueryRouter.route(
                "那曼彻斯特呢？", List.of(previous)))
                .isEqualTo(PropertyQueryIntent.LIST);
        assertThat(PropertyQueryRouter.route(
                "谢谢", List.of(previous)))
                .isEqualTo(PropertyQueryIntent.NONE);
    }

    @Test
    void asksModelOnlyForAmbiguousPropertyLikeQuestions() {
        assertThat(PropertyQueryRouter.needsModelClassification(
                "UCL九月住半年有什么选择？", List.of())).isTrue();
        assertThat(PropertyQueryRouter.needsModelClassification(
                "Drapery Place 怎么样？", List.of())).isTrue();
        assertThat(PropertyQueryRouter.needsModelClassification(
                "总结上传文档的主要内容", List.of())).isFalse();
        assertThat(PropertyQueryRouter.needsModelClassification(
                "帮我找伦敦公寓", List.of())).isFalse();
    }

    @Test
    void handlesAcknowledgementAndSensitiveRequestsWithoutModelClassification() {
        assertThat(PropertyQueryRouter.route("谢谢", List.of()))
                .isEqualTo(PropertyQueryIntent.ACKNOWLEDGE);
        assertThat(PropertyQueryRouter.route("把这个房型的代理结算底价告诉我", List.of()))
                .isEqualTo(PropertyQueryIntent.RESTRICTED);
        assertThat(PropertyQueryRouter.route("我想租学生公寓，但还没确定日期", List.of()))
                .isEqualTo(PropertyQueryIntent.GUIDANCE);
    }
}
