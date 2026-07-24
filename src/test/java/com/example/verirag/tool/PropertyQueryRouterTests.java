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
    void routesShortFollowUpFromPropertyHistory() {
        ChatMessage previous = new ChatMessage();
        previous.setRole("USER");
        previous.setContent("伦敦有哪些公寓？");

        assertThat(PropertyQueryRouter.isStructuredPropertyQuery(
                "那曼彻斯特呢？", List.of(previous))).isTrue();
    }
}
