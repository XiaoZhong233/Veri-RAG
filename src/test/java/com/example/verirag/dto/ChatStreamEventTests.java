package com.example.verirag.dto;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ChatStreamEventTests {

    @Test
    void createsSseErrorEventWithSessionAndMessage() {
        ChatStreamEvent event = ChatStreamEvent.error(42L, "模型响应超时");

        assertThat(event.getType()).isEqualTo("error");
        assertThat(event.getSessionId()).isEqualTo(42L);
        assertThat(event.getContent()).isEqualTo("模型响应超时");
        assertThat(event.getReferences()).isNull();
    }
}
