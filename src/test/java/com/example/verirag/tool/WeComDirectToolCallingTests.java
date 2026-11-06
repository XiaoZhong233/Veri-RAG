package com.example.verirag.tool;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatOptions;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 使用真实 Spring AI 工具循环，证明少的是模型请求，而不只是少生成一个字符串。 */
class WeComDirectToolCallingTests {
    @Test
    void weComReturnsAfterDatabaseToolWithoutFinalModelRound() {
        var calls = new AtomicInteger();
        var client = ChatClient.create(model(calls));
        var selector = selector();
        String content = client.prompt().user("找房")
                .toolCallbacks(selector.callbacksFor(PropertyQueryIntent.RECOMMEND, true)).call().content();
        assertEquals(1, calls.get());
        assertTrue(content.contains("Property search completed"));
        // 其它请求仍使用原 metadata，微信的包装不能污染共用回调。
        assertFalse(selector.callbacksFor(PropertyQueryIntent.RECOMMEND)[0].getToolMetadata().returnDirect());
    }

    @Test
    void webStillSendsToolResultBackToModelForFullAnswer() {
        var calls = new AtomicInteger();
        String content = ChatClient.create(model(calls)).prompt().user("找房")
                .toolCallbacks(selector().callbacksFor(PropertyQueryIntent.RECOMMEND)).call().content();
        assertEquals(2, calls.get());
        assertEquals("Model-composed answer", content);
    }

    private ChatModel model(AtomicInteger calls) {
        var model = mock(ChatModel.class);
        when(model.getOptions()).thenReturn(OpenAiChatOptions.builder().build());
        when(model.call(any(Prompt.class))).thenAnswer(call -> {
            AssistantMessage message = calls.incrementAndGet() == 1
                    ? AssistantMessage.builder().content("").toolCalls(List.of(
                    new AssistantMessage.ToolCall("lookup-1", "function", "search_room_offers", "{}"))).build()
                    : AssistantMessage.builder().content("Model-composed answer").build();
            return new ChatResponse(List.of(new Generation(message)));
        });
        return model;
    }

    private PropertyToolSelector selector() {
        var tools = mock(PropertyQueryTools.class);
        when(tools.searchRoomOffers(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(new PropertyQueryTools.RoomOfferSearchResult("London", null, List.of(), null,
                        null, null, null, null, "REFERENCE", 0, 0, 0, List.of(), List.of()));
        return new PropertyToolSelector(tools);
    }
}
