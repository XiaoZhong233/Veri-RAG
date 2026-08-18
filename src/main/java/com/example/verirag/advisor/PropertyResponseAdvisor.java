package com.example.verirag.advisor;

import com.example.verirag.tool.PropertyPriceGuard;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Objects;

/**
 * 房源回答的代码层最终出口。
 * <p>
 * 仅在请求显式设置 {@link #ENABLED} 时生效，确保价格脱敏和顾问确认提示不依赖模型遵守 Prompt。
 */
@Component
@RequiredArgsConstructor
public class PropertyResponseAdvisor implements CallAdvisor {

    public static final String ENABLED = "property.response.guard.enabled";
    public static final String QUESTION = "property.response.guard.question";

    private final PropertyPriceGuard propertyPriceGuard;

    @Override
    public ChatClientResponse adviseCall(ChatClientRequest request, CallAdvisorChain chain) {
        ChatClientResponse response = chain.nextCall(request);
        if (!Boolean.TRUE.equals(request.context().get(ENABLED))) {
            return response;
        }
        String question = Objects.toString(request.context().get(QUESTION), "");
        return enforce(response, question);
    }

    private ChatClientResponse enforce(ChatClientResponse response, String question) {
        if (response == null || response.chatResponse() == null
                || response.chatResponse().getResults() == null) {
            return response;
        }
        List<Generation> generations = response.chatResponse().getResults().stream()
                .map(generation -> enforce(generation, question))
                .toList();
        ChatResponse guardedResponse = ChatResponse.builder()
                .from(response.chatResponse())
                .generations(generations)
                .build();
        return response.mutate().chatResponse(guardedResponse).build();
    }

    private Generation enforce(Generation generation, String question) {
        AssistantMessage output = generation.getOutput();
        if (output == null) {
            return generation;
        }
        AssistantMessage guardedOutput = AssistantMessage.builder()
                .content(propertyPriceGuard.enforce(output.getText(), question))
                .properties(output.getMetadata())
                .toolCalls(output.getToolCalls())
                .media(output.getMedia())
                .build();
        return new Generation(guardedOutput, generation.getMetadata());
    }

    @Override
    public String getName() {
        return "property-response-guard";
    }

    @Override
    public int getOrder() {
        // 位于 ChatMemory Advisor 外层，在完整模型/Tool 调用结束后统一处理最终回答。
        return Ordered.HIGHEST_PRECEDENCE + 100;
    }
}
