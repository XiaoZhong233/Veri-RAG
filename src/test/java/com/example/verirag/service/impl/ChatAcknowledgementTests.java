package com.example.verirag.service.impl;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ChatAcknowledgementTests {

    @Test
    void handoffReturnsActionAfterOneClassificationWithoutGeneratingAnswer() throws Exception {
        var classifier = org.mockito.Mockito.mock(com.example.verirag.tool.PropertyIntentClassifier.class);
        var service = org.mockito.Mockito.mock(ChatServiceImpl.class, org.mockito.Mockito.CALLS_REAL_METHODS);
        org.springframework.test.util.ReflectionTestUtils.setField(service, "propertyIntentClassifier", classifier);
        var request = new com.example.verirag.dto.ChatAskRequest();
        request.setQuestion("我想和真人聊");
        request.setAllowHumanHandoff(true);
        org.mockito.Mockito.when(classifier.resolve(request.getQuestion(), java.util.List.of(), true))
                .thenReturn(com.example.verirag.tool.PropertyQueryIntent.HUMAN_HANDOFF);

        var result = service.ask(1L, request);

        assertThat(result.isHumanHandoff()).isTrue();
        assertThat(result.getAnswer()).isNull();
        org.mockito.Mockito.verify(classifier).resolve(request.getQuestion(), java.util.List.of(), true);
        org.mockito.Mockito.verifyNoMoreInteractions(classifier);
    }

    @Test
    void webJsonCannotEnableInternalHandoffAction() throws Exception {
        var request = new com.fasterxml.jackson.databind.ObjectMapper().readValue(
                "{\"question\":\"转人工\",\"allowHumanHandoff\":true}",
                com.example.verirag.dto.ChatAskRequest.class);
        assertThat(request.isAllowHumanHandoff()).isFalse();
    }

    @Test
    void returnsShortLocalizedAcknowledgementWithoutModelContent() {
        assertThat(ChatServiceImpl.acknowledgementAnswer("好吧"))
                .isEqualTo("好的，有需要随时告诉我。");
        assertThat(ChatServiceImpl.acknowledgementAnswer("OK, thanks"))
                .isEqualTo("Of course. Let me know whenever you need anything else.");
    }
}
