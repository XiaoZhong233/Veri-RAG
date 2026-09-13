package com.example.verirag.memory;

import com.example.verirag.entity.ChatMessage;
import com.example.verirag.entity.ChatSession;
import com.example.verirag.mapper.ChatMessageMapper;
import com.example.verirag.mapper.ChatSessionMapper;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class ConversationContextTests {
    private final ChatMessageMapper messages = mock(ChatMessageMapper.class);
    private final ChatSessionMapper sessions = mock(ChatSessionMapper.class);
    private final ConversationContextService service = new ConversationContextService(messages, sessions);

    @Test
    void keepsEarlyBudgetUntilSummarySuccessfullyCoversIt() {
        var session = new ChatSession();
        when(sessions.selectById(1L)).thenReturn(session);
        when(messages.countBySessionId(1L)).thenReturn(10L);
        var rows = rows(10);
        rows.getFirst().setContent("预算£350，9月入住");
        rows.get(8).setContent("预算改成£400，其他不变");
        when(messages.listRecentBySessionId(1L, 10)).thenReturn(rows);
        var pending = service.load(1L);
        assertThat(pending.messages()).hasSize(10);
        assertThat(pending.messages().getFirst().getContent()).contains("£350");
        assertThat(pending.messages().get(8).getContent()).contains("£400");

        session.setMemorySummary("预算£350，9月入住");
        session.setSummarizedMessageCount(2);
        when(messages.listRecentBySessionId(1L, 8)).thenReturn(rows.subList(2, 10));
        var completed = service.load(1L);
        assertThat(completed.summary()).contains("£350");
        assertThat(completed.messages()).hasSize(8);
        assertThat(completed.messages().get(6).getContent()).contains("£400");
    }

    @Test
    void boundedBacklogUsesNewestMessagesWithoutModifyingDatabaseObjects() {
        when(sessions.selectById(1L)).thenReturn(new ChatSession());
        when(messages.countBySessionId(1L)).thenReturn(100L);
        var rows = rows(16);
        rows.forEach(row -> row.setContent("新".repeat(1000)));
        when(messages.listRecentBySessionId(1L, 16)).thenReturn(rows);
        var context = service.load(1L);
        assertThat(context.messages()).hasSize(8);
        assertThat(context.messages().stream().mapToInt(row -> row.getContent().length()).sum()).isEqualTo(8000);
        assertThat(context.messages().getLast().getId()).isEqualTo(16L);
        assertThat(rows).hasSize(16);
    }

    @Test
    void configuredWindowIsSharedWithNativeMemoryAndDoesNotDependOnDate() {
        ReflectionTestUtils.setField(service, "recentMessages", 10);
        when(sessions.selectById(1L)).thenReturn(new ChatSession());
        when(messages.countBySessionId(1L)).thenReturn(10L);
        var rows = rows(10);
        rows.getFirst().setContent("昨天讨论公寓A，当前改看公寓B");
        when(messages.listRecentBySessionId(1L, 10)).thenReturn(rows);
        var repository = new MyBatisChatMemoryRepository(service);
        assertThat(repository.findByConversationId("1")).hasSize(11);
        assertThat(repository.findByConversationId("1").getFirst().getText())
                .contains("不得因历史中的转人工");
        assertThat(service.recentMessages()).isEqualTo(10);
    }

    @Test
    void shortensOnlyModelCopyAndDoesNotSplitSurrogatePair() {
        var row = rows(1).getFirst();
        row.setContent("你好😀预算400");
        var limited = ConversationContextService.boundedMessages(List.of(row), 3);
        assertThat(limited.getFirst().getContent()).isEqualTo("你好");
        assertThat(row.getContent()).isEqualTo("你好😀预算400");
    }

    static List<ChatMessage> rows(int count) {
        return IntStream.rangeClosed(1, count).mapToObj(i -> {
            var row = new ChatMessage();
            row.setId((long) i);
            row.setRole(i % 2 == 0 ? "ASSISTANT" : "USER");
            row.setContent("消息" + i);
            return row;
        }).toList();
    }
}
