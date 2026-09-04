package com.example.verirag.advisor;

import com.example.verirag.tool.PropertyPriceGuard;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class PropertyResponseAdvisorTests {

    private final PropertyResponseAdvisor advisor =
            new PropertyResponseAdvisor(new PropertyPriceGuard());

    @Test
    void preservesReferencePriceAndAddsChineseNoticeWhenEnabled() {
        ChatClientResponse response = advisor.adviseCall(
                request(true, "这个公寓多少钱？"),
                chainReturning("这个房型每周 £430。"));

        assertThat(response.chatResponse().getResult().getOutput().getText())
                .contains("£430")
                .endsWith("参考价格及可订状态须由 Londonist 顾问最终确认。");
    }

    @Test
    void usesEnglishNoticeFromQuestionLanguage() {
        ChatClientResponse response = advisor.adviseCall(
                request(true, "Is this apartment available?"),
                chainReturning("It is available."));

        assertThat(response.chatResponse().getResult().getOutput().getText())
                .endsWith("Reference pricing and availability must be confirmed by a Londonist consultant.")
                .doesNotContain("参考价格及可订状态须由 Londonist 顾问最终确认。");
    }

    @Test
    void leavesNonPropertyResponseUnchangedWhenDisabled() {
        ChatClientResponse response = advisor.adviseCall(
                request(false, "合同流程是什么？"),
                chainReturning("请先阅读合同。"));

        assertThat(response.chatResponse().getResult().getOutput().getText())
                .isEqualTo("请先阅读合同。");
    }

    private static ChatClientRequest request(boolean enabled, String question) {
        return new ChatClientRequest(new Prompt(question), Map.of(
                PropertyResponseAdvisor.ENABLED, enabled,
                PropertyResponseAdvisor.QUESTION, question));
    }

    private static CallAdvisorChain chainReturning(String answer) {
        ChatClientResponse response = ChatClientResponse.builder()
                .chatResponse(new ChatResponse(List.of(
                        new Generation(new AssistantMessage(answer)))))
                .build();
        return new CallAdvisorChain() {
            @Override
            public ChatClientResponse nextCall(ChatClientRequest request) {
                return response;
            }

            @Override
            public List<CallAdvisor> getCallAdvisors() {
                return List.of();
            }

            @Override
            public CallAdvisorChain copy(CallAdvisor advisor) {
                return this;
            }
        };
    }
}
