package com.example.verirag.memory;

import com.example.verirag.entity.ChatMessage;
import com.example.verirag.entity.ChatSession;
import com.example.verirag.mapper.ChatMessageMapper;
import com.example.verirag.mapper.ChatSessionMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.ArrayList;

/**
 * 将既有 t_chat_message 适配为 Spring AI ChatMemoryRepository。
 *
 * 消息正文与引用仍由 ChatService 在模型完成后原子持久化；Advisor 负责读取并注入历史。
 * 这样不会让 Advisor 写入携带 RAG 上下文的临时 prompt，也不会丢失 refs 字段。
 */
@Component
@RequiredArgsConstructor
public class MyBatisChatMemoryRepository implements ChatMemoryRepository {

    private static final int RECENT_MESSAGES = 4;

    private final ChatMessageMapper chatMessageMapper;
    private final ChatSessionMapper chatSessionMapper;

    @Override
    public List<String> findConversationIds() {
        // 当前业务通过用户维度查询会话列表，此接口不是主链路所需。
        return List.of();
    }

    @Override
    public List<Message> findByConversationId(String conversationId) {
        Long sessionId = parseSessionId(conversationId);
        if (sessionId == null) {
            return List.of();
        }
        ChatSession session = chatSessionMapper.selectById(sessionId);
        if (session == null) {
            return List.of();
        }
        List<ChatMessage> rows = chatMessageMapper.listRecentBySessionId(sessionId, RECENT_MESSAGES);
        List<Message> result = new ArrayList<>(RECENT_MESSAGES + 1);
        if (session.getMemorySummary() != null && !session.getMemorySummary().isBlank()) {
            result.add(new SystemMessage("以下是本会话较早内容的压缩摘要，仅用于保持对话连续性：\n"
                    + session.getMemorySummary().strip()));
        }
        rows.stream().map(this::toMessage).forEach(result::add);
        return result;
    }

    @Override
    public void saveAll(String conversationId, List<Message> messages) {
        // 持久化由 ChatService 统一处理，以保留 assistant refs JSON 并避免存入带知识片段的 prompt。
    }

    @Override
    public void deleteByConversationId(String conversationId) {
        // 会话删除由 ChatService 的 deleteSession 统一处理。
    }

    private Message toMessage(ChatMessage message) {
        return "ASSISTANT".equals(message.getRole())
                ? new AssistantMessage(message.getContent())
                : new UserMessage(message.getContent());
    }

    private static Long parseSessionId(String conversationId) {
        try {
            return Long.valueOf(conversationId);
        }
        catch (RuntimeException ignored) {
            return null;
        }
    }
}
