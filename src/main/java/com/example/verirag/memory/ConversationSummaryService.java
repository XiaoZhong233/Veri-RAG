package com.example.verirag.memory;

import com.example.verirag.entity.ChatMessage;
import com.example.verirag.entity.ChatSession;
import com.example.verirag.mapper.ChatMessageMapper;
import com.example.verirag.mapper.ChatSessionMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 将早期对话压缩为短摘要，完整消息不删除，仍可供会话历史页面展示。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ConversationSummaryService {

    private static final String SUMMARY_SYSTEM_PROMPT = """
            你负责压缩企业知识库问答的早期会话记忆。
            仅保留：用户明确身份或偏好、已确认的事实或结论、用户仍待解决的问题、必要的指代关系。
            不要保留寒暄、重复内容、完整知识库片段、引用编号或推理过程。
            使用简洁中文项目符号，总长度不超过 300 个中文字符。
            """;

    private final ChatSessionMapper chatSessionMapper;
    private final ChatMessageMapper chatMessageMapper;
    @Qualifier("summaryChatClient")
    private final ChatClient summaryChatClient;
    private final Set<Long> inFlightSessions = ConcurrentHashMap.newKeySet();

    @Value("${rag.memory.enabled:true}")
    private boolean enabled;

    @Value("${rag.memory.recent-messages:4}")
    private int recentMessages;

    @Value("${rag.memory.summary-trigger-messages:6}")
    private int summaryTriggerMessages;

    /**
     * 每积累指定数量的新消息后在后台生成摘要；当前请求不等待它完成。
     */
    @Async("conversationSummaryExecutor")
    public void maybeSummarize(Long sessionId) {
        if (!enabled || sessionId == null || !inFlightSessions.add(sessionId)) {
            return;
        }
        try {
            ChatSession session = chatSessionMapper.selectById(sessionId);
            if (session == null) {
                return;
            }
            List<ChatMessage> messages = chatMessageMapper.listBySessionId(sessionId);
            int summarizedCount = Math.max(session.getSummarizedMessageCount() == null
                    ? 0 : session.getSummarizedMessageCount(), 0);
            int keepCount = Math.max(recentMessages, 2);
            int triggerCount = Math.max(summaryTriggerMessages, 2);
            if (messages.size() - summarizedCount < keepCount + triggerCount) {
                return;
            }

            int newSummarizedCount = messages.size() - keepCount;
            if (newSummarizedCount <= summarizedCount) {
                return;
            }
            String source = buildSummaryInput(session.getMemorySummary(),
                    messages.subList(summarizedCount, newSummarizedCount));
            String summary = summaryChatClient.prompt()
                    .system(SUMMARY_SYSTEM_PROMPT)
                    .user(source)
                    .call()
                    .content();
            if (summary == null || summary.isBlank()) {
                log.warn("Conversation summary was empty: sessionId={}", sessionId);
                return;
            }
            chatSessionMapper.updateMemorySummary(sessionId, summary.strip(), newSummarizedCount);
            log.info("event=chat.memory.summarized sessionId={} summarizedMessages={} retainedMessages={}",
                    sessionId, newSummarizedCount, keepCount);
        }
        catch (Exception ex) {
            // 摘要失败不影响主问答；下次新消息到来时会自动重试。
            log.warn("Conversation summary failed: sessionId={}, error={}", sessionId, ex.toString());
        }
        finally {
            inFlightSessions.remove(sessionId);
        }
    }

    private static String buildSummaryInput(String previousSummary, List<ChatMessage> messages) {
        StringBuilder input = new StringBuilder();
        if (previousSummary != null && !previousSummary.isBlank()) {
            input.append("已有摘要：\n").append(previousSummary.strip()).append("\n\n");
        }
        input.append("需要合并的新对话：\n");
        for (ChatMessage message : messages) {
            String role = "ASSISTANT".equals(message.getRole()) ? "助手" : "用户";
            input.append(role).append("：").append(message.getContent() == null ? "" : message.getContent().strip())
                    .append("\n");
        }
        return input.toString();
    }
}
