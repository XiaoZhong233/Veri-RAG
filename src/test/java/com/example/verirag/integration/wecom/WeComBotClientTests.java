package com.example.verirag.integration.wecom;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class WeComBotClientTests {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void buildsGroupConversationKeyFromChatId() throws Exception {
        var body = objectMapper.readTree("""
                {
                  "chattype": "group",
                  "chatid": "chat-123",
                  "from": {"userid": "user-ignored"}
                }
                """);

        assertThat(WeComBotClient.conversationKey(body))
                .isEqualTo("group:chat-123");
    }

    @Test
    void buildsSingleConversationKeyFromSender() throws Exception {
        var body = objectMapper.readTree("""
                {
                  "chattype": "single",
                  "from": {"userid": "user-456"}
                }
                """);

        assertThat(WeComBotClient.conversationKey(body))
                .isEqualTo("single:user-456");
    }

    @Test
    void stripsConfiguredGroupMentionContainingSpaces() {
        assertThat(WeComBotClient.normalizeQuestion(
                "  @londonist 助手   伦敦一区有哪些房源？ ",
                "londonist 助手",
                true))
                .isEqualTo("伦敦一区有哪些房源？");
    }

    @Test
    void configuredMentionWithoutQuestionBecomesEmpty() {
        assertThat(WeComBotClient.normalizeQuestion(
                " @londonist 助手 ",
                "londonist 助手",
                true))
                .isEmpty();
    }

    @Test
    void keepsSingleChatQuestionUnchanged() {
        assertThat(WeComBotClient.normalizeQuestion(
                "  伦敦一区有哪些房源？ ",
                "londonist 助手",
                false))
                .isEqualTo("伦敦一区有哪些房源？");
    }

    @Test
    void fallsBackToSingleTokenMentionWhenDisplayNameDoesNotMatch() {
        assertThat(WeComBotClient.normalizeQuestion(
                "@智能房源助手 伦敦一区有哪些房源？",
                "londonist 助手",
                true))
                .isEqualTo("伦敦一区有哪些房源？");
    }
}
