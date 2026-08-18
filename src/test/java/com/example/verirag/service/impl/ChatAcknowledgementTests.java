package com.example.verirag.service.impl;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ChatAcknowledgementTests {

    @Test
    void returnsShortLocalizedAcknowledgementWithoutModelContent() {
        assertThat(ChatServiceImpl.acknowledgementAnswer("好吧"))
                .isEqualTo("好的，有需要随时告诉我。");
        assertThat(ChatServiceImpl.acknowledgementAnswer("OK, thanks"))
                .isEqualTo("Of course. Let me know whenever you need anything else.");
    }
}
