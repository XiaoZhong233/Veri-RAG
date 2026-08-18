package com.example.verirag.dto;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ChatStreamEventTests {

    @Test
    void intentDoneCarriesMachineReadableIntent() {
        ChatStreamEvent event = ChatStreamEvent.intentDone("CLARIFY", "需要进一步确认");

        assertThat(event.getType()).isEqualTo("intent_done");
        assertThat(event.getIntent()).isEqualTo("CLARIFY");
        assertThat(event.getContent()).isEqualTo("需要进一步确认");
    }

    @Test
    void createsSseErrorEventWithSessionAndMessage() {
        ChatStreamEvent event = ChatStreamEvent.error(42L, "模型响应超时");

        assertThat(event.getType()).isEqualTo("error");
        assertThat(event.getSessionId()).isEqualTo(42L);
        assertThat(event.getContent()).isEqualTo("模型响应超时");
        assertThat(event.getReferences()).isNull();
        assertThat(event.getToolName()).isNull();
    }

    @Test
    void createsToolProgressEvents() {
        ChatStreamEvent started =
                ChatStreamEvent.toolStart("search_room_offers", "正在查询房源");
        ChatStreamEvent completed =
                ChatStreamEvent.toolDone("search_room_offers", "房源查询完成");

        assertThat(started.getType()).isEqualTo("tool_start");
        assertThat(started.getToolName()).isEqualTo("search_room_offers");
        assertThat(started.getContent()).isEqualTo("正在查询房源");
        assertThat(completed.getType()).isEqualTo("tool_done");
        assertThat(ChatStreamEvent.toolError("search_room_offers", "查询失败").getType())
                .isEqualTo("tool_error");
    }
}
