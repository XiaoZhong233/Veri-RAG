package com.example.verirag.service.impl;

import com.example.verirag.advisor.RetrievalTimingAdvisor;
import com.example.verirag.dto.ChatAskRequest;
import com.example.verirag.dto.ChatAskResult;
import com.example.verirag.memory.ConversationSummaryService;
import com.example.verirag.observability.RagMetrics;
import com.example.verirag.prompt.PropertyToolPromptManager;
import com.example.verirag.prompt.SalesRecommendationPromptManager;
import com.example.verirag.tool.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class WeComRecommendationWorkflowTests {
    private final ChatModel model = mock(ChatModel.class);
    private final PropertyIntentClassifier classifier = mock(PropertyIntentClassifier.class);
    private final ChatServiceImpl service = mock(ChatServiceImpl.class, CALLS_REAL_METHODS);
    private final AtomicInteger calls = new AtomicInteger();

    WeComRecommendationWorkflowTests() {
        var metrics = mock(RagMetrics.class);
        var residences = mock(com.example.verirag.mapper.ResidenceMapper.class);
        when(residences.selectList(any(com.baomidou.mybatisplus.core.conditions.Wrapper.class))).thenReturn(List.of());
        var tools = new PropertyQueryTools(residences, null, null, null, null, null);
        set("chatClient", ChatClient.create(model));
        set("propertyToolSelector", new PropertyToolSelector(tools));
        set("propertyIntentClassifier", classifier);
        set("propertyPriceGuard", new PropertyPriceGuard());
        var ragPrompt = mock(com.example.verirag.prompt.RagPromptManager.class);
        when(ragPrompt.noContext()).thenReturn("");
        set("promptManager", ragPrompt);
        set("ragMetrics", metrics);
        set("retrievalTimingAdvisor", new RetrievalTimingAdvisor(metrics, ObservationRegistry.NOOP));
        set("objectMapper", new ObjectMapper());
        set("llmResponseTimeout", Duration.ofSeconds(5));
        set("propertyMaxTokens", 10000);
        var sales = mock(SalesRecommendationPromptManager.class);
        when(sales.systemPrompt()).thenReturn("");
        set("salesRecommendationPromptManager", sales);
        var prompt = mock(PropertyToolPromptManager.class);
        when(prompt.systemPrompt()).thenReturn("Web system prompt");
        set("propertyToolPromptManager", prompt);
        var transactions = mock(TransactionTemplate.class);
        when(transactions.execute(any())).thenReturn(7L);
        set("transactionTemplate", transactions);
        set("conversationSummaryService", mock(ConversationSummaryService.class));
        when(classifier.resolve(anyString(), anyList(), eq(true))).thenReturn(PropertyQueryIntent.RECOMMEND);
        when(classifier.resolve(anyString(), anyList())).thenReturn(PropertyQueryIntent.RECOMMEND);
        when(model.getOptions()).thenReturn(OpenAiChatOptions.builder().build());
        when(model.call(any(Prompt.class))).thenAnswer(call -> {
            AssistantMessage message = calls.incrementAndGet() == 1
                    ? AssistantMessage.builder().content("").toolCalls(List.of(new AssistantMessage.ToolCall("lookup", "function",
                    "search_room_offers", "{\"city\":\"London\",\"startDateFrom\":\"2026-12-01\",\"stayWeeks\":26}"))).build()
                    : AssistantMessage.builder().content("Full web answer").build();
            return new ChatResponse(List.of(new Generation(message)));
        });
    }

    @Test
    void weComZeroResultsUsesOneModelCallAndNeverExposesToolJson() throws Exception {
        ChatAskResult result = service.ask(2L, request(true));
        assertEquals(1, calls.get());
        assertTrue(result.getAnswer().contains("暂时没有找到"));
        assertFalse(result.getAnswer().contains("Property search completed"));
        assertFalse(result.getAnswer().contains("matchedResidenceCount"));
        assertNotNull(result.getPropertyRecommendation());
    }

    @Test
    void webKeepsModelComposedResponseAndSecondModelCall() throws Exception {
        var result = service.ask(2L, request(false));
        assertEquals(2, calls.get());
        assertEquals("Full web answer", result.getAnswer());
    }

    @Test
    void structuredContextReachesBothIntentClassifierAndToolPlanningPrompt() throws Exception {
        var req = request(true);
        req.setQuestion("半年");
        req.setPropertySearchContext(Map.of("nearbyPlaceKeyword", "UCL", "maxWeeklyPrice", 500,
                "startDateFrom", "2026-12-01", "preferredTravelModes", "TUBE"));
        service.ask(2L, req);
        var prompts = org.mockito.ArgumentCaptor.forClass(Prompt.class);
        verify(model).call(prompts.capture());
        String input = prompts.getValue().getUserMessage().getText();
        assertTrue(input.contains("半年"));
        assertTrue(input.contains("UCL"));
        assertTrue(input.contains("500"));
        assertTrue(input.contains("2026-12-01"));
        assertTrue(input.contains("取消的条件应清空"));
        verify(classifier).resolve(eq("半年"), argThat(history -> history.stream()
                .anyMatch(m -> m.getContent().contains("UCL"))), eq(true));
    }

    @Test
    void modelSkippingTheToolCannotLeakInventedPropertyData() throws Exception {
        when(model.call(any(Prompt.class))).thenReturn(new ChatResponse(List.of(new Generation(
                AssistantMessage.builder().content("{\"fakeProperty\":\"Imaginary Residence\"}").build()))));
        var result = service.ask(2L, request(true));
        assertTrue(result.getAnswer().contains("暂时没能查到房源信息"));
        assertFalse(result.getAnswer().contains("Imaginary Residence"));
        assertNull(result.getPropertyRecommendation());
    }

    private ChatAskRequest request(boolean weCom) {
        var req = new ChatAskRequest();
        req.setQuestion("12月起租半年有什么公寓");
        req.setPlainText(weCom);
        req.setWeComKf(weCom);
        req.setAllowHumanHandoff(weCom);
        return req;
    }
    private void set(String field, Object value) { ReflectionTestUtils.setField(service, field, value); }
}
