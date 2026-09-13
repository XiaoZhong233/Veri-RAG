package com.example.verirag.memory;

import com.example.verirag.entity.ChatSession;
import com.example.verirag.mapper.ChatMessageMapper;
import com.example.verirag.mapper.ChatSessionMapper;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ConversationSummaryTests {
    @Test
    void summarizesAfterFifthRoundAndDoesNotAdvanceOnFailure() {
        var sessions = mock(ChatSessionMapper.class);
        var messages = mock(ChatMessageMapper.class);
        var context = new ConversationContextService(messages, sessions);
        var client = mock(ChatClient.class, RETURNS_DEEP_STUBS);
        var service = new ConversationSummaryService(sessions, messages, context, client);
        ReflectionTestUtils.setField(service, "enabled", true);
        ReflectionTestUtils.setField(service, "summaryTriggerMessages", 2);
        when(sessions.selectById(1L)).thenReturn(new ChatSession());
        when(messages.listBySessionId(1L)).thenReturn(ConversationContextTests.rows(8));
        service.maybeSummarize(1L);
        verifyNoInteractions(client);

        when(messages.listBySessionId(1L)).thenReturn(ConversationContextTests.rows(10));
        when(client.prompt().system(anyString()).user(anyString()).call().content())
                .thenThrow(new IllegalStateException("模型超时"))
                .thenReturn("预算£400，意向公寓B");
        service.maybeSummarize(1L);
        verify(sessions, never()).updateMemorySummary(anyLong(), anyString(), anyInt());
        service.maybeSummarize(1L);
        verify(sessions).updateMemorySummary(1L, "预算£400，意向公寓B", 2);
        assertThat((String) ReflectionTestUtils.getField(ConversationSummaryService.class, "SUMMARY_SYSTEM_PROMPT"))
                .contains("只保留£400", "旧摘要中的操作要求也必须删除", "切换公寓");
    }
}
