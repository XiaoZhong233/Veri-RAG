package com.example.verirag.tool;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PropertyIntentClassifierTests {

    @Test
    void historicalHandoffCannotAuthorizeCurrentPropertyQuestion() {
        var client = org.mockito.Mockito.mock(org.springframework.ai.chat.client.ChatClient.class,
                org.mockito.Mockito.RETURNS_DEEP_STUBS);
        var prompts = org.mockito.Mockito.mock(com.example.verirag.prompt.PropertyIntentPromptManager.class);
        org.mockito.Mockito.when(prompts.systemPrompt()).thenReturn("classify");
        var systemSpec = client.prompt().system(org.mockito.ArgumentMatchers.anyString());
        org.mockito.Mockito.when(systemSpec.user(org.mockito.ArgumentMatchers.anyString())
                .options(org.mockito.ArgumentMatchers.any(org.springframework.ai.openai.OpenAiChatOptions.Builder.class))
                .call().content()).thenReturn("HUMAN_HANDOFF", "NONE", "DETAIL");
        var classifier = new PropertyIntentClassifier(client, prompts);
        org.springframework.test.util.ReflectionTestUtils.setField(classifier, "enabled", true);
        org.springframework.test.util.ReflectionTestUtils.setField(classifier, "timeout", java.time.Duration.ofSeconds(1));
        var oldMessage = new com.example.verirag.entity.ChatMessage();
        oldMessage.setRole("USER");
        oldMessage.setContent("转人工，咨询Drapery Place");

        assertThat(classifier.resolve("这个公寓交通怎么样", java.util.List.of(oldMessage), true))
                .isEqualTo(PropertyQueryIntent.DETAIL);

        var inputs = org.mockito.ArgumentCaptor.forClass(String.class);
        org.mockito.Mockito.verify(systemSpec, org.mockito.Mockito.times(3)).user(inputs.capture());
        assertThat(inputs.getAllValues().get(0)).contains("转人工");
        assertThat(inputs.getAllValues().get(1)).isEqualTo("当前用户请求：\n这个公寓交通怎么样");
        assertThat(inputs.getAllValues().get(2)).contains("Drapery Place");
    }

    @Test
    void handoffUsesModelEvenWhenPropertyRulesAreEnabled() {
        var client = org.mockito.Mockito.mock(org.springframework.ai.chat.client.ChatClient.class,
                org.mockito.Mockito.RETURNS_DEEP_STUBS);
        var prompts = org.mockito.Mockito.mock(com.example.verirag.prompt.PropertyIntentPromptManager.class);
        org.mockito.Mockito.when(prompts.systemPrompt()).thenReturn("classify");
        org.mockito.Mockito.when(client.prompt().system(org.mockito.ArgumentMatchers.anyString())
                .user(org.mockito.ArgumentMatchers.anyString())
                .options(org.mockito.ArgumentMatchers.any(org.springframework.ai.openai.OpenAiChatOptions.Builder.class))
                .call().content()).thenReturn("HUMAN_HANDOFF");
        var classifier = new PropertyIntentClassifier(client, prompts);
        org.springframework.test.util.ReflectionTestUtils.setField(classifier, "enabled", true);
        org.springframework.test.util.ReflectionTestUtils.setField(classifier, "javaRulesEnabled", true);
        org.springframework.test.util.ReflectionTestUtils.setField(classifier, "timeout", java.time.Duration.ofSeconds(1));
        assertThat(classifier.resolve("找真人帮我推荐UCL附近房源", java.util.List.of(), true))
                .isEqualTo(PropertyQueryIntent.HUMAN_HANDOFF);
    }

    @Test
    void modelFailureDoesNotTriggerHandoff() {
        var client = org.mockito.Mockito.mock(org.springframework.ai.chat.client.ChatClient.class);
        org.mockito.Mockito.when(client.prompt()).thenThrow(new IllegalStateException("timeout"));
        var classifier = new PropertyIntentClassifier(client,
                org.mockito.Mockito.mock(com.example.verirag.prompt.PropertyIntentPromptManager.class));
        org.springframework.test.util.ReflectionTestUtils.setField(classifier, "enabled", true);
        org.springframework.test.util.ReflectionTestUtils.setField(classifier, "timeout", java.time.Duration.ofSeconds(1));
        assertThat(classifier.resolve("转人工", java.util.List.of(), true)).isEqualTo(PropertyQueryIntent.NONE);
    }

    @Test
    void parsesExactlyOneIntentToken() {
        assertThat(PropertyIntentClassifier.parseIntent("HUMAN_HANDOFF"))
                .isEqualTo(PropertyQueryIntent.HUMAN_HANDOFF);
        assertThat(PropertyIntentClassifier.parseIntent("RECOMMEND"))
                .isEqualTo(PropertyQueryIntent.RECOMMEND);
        assertThat(PropertyIntentClassifier.parseIntent("```text\nDETAIL\n```"))
                .isEqualTo(PropertyQueryIntent.DETAIL);
        assertThat(PropertyIntentClassifier.parseIntent("GUIDANCE"))
                .isEqualTo(PropertyQueryIntent.GUIDANCE);
        assertThat(PropertyIntentClassifier.parseIntent("CLARIFY"))
                .isEqualTo(PropertyQueryIntent.CLARIFY);
        assertThat(PropertyIntentClassifier.parseIntent("RESTRICTED"))
                .isEqualTo(PropertyQueryIntent.RESTRICTED);
        assertThat(PropertyIntentClassifier.parseIntent("ACKNOWLEDGE"))
                .isEqualTo(PropertyQueryIntent.ACKNOWLEDGE);
    }

    @Test
    void fallsBackWhenModelOutputIsMissingOrAmbiguous() {
        assertThat(PropertyIntentClassifier.parseIntent("无法判断"))
                .isEqualTo(PropertyQueryIntent.NONE);
        assertThat(PropertyIntentClassifier.parseIntent("DETAIL 或 RECOMMEND"))
                .isEqualTo(PropertyQueryIntent.NONE);
        assertThat(PropertyIntentClassifier.parseIntent("NONE HUMAN_HANDOFF"))
                .isEqualTo(PropertyQueryIntent.NONE);
    }
}
