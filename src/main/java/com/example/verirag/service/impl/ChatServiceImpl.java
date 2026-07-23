package com.example.verirag.service.impl;

import com.example.verirag.dto.ChatAskRequest;
import com.example.verirag.dto.ChatAskResult;
import com.example.verirag.entity.ChatMessage;
import com.example.verirag.entity.ChatSession;
import com.example.verirag.exception.BusinessException;
import com.example.verirag.mapper.ChatMessageMapper;
import com.example.verirag.mapper.ChatSessionMapper;
import com.example.verirag.service.ChatService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.Filter;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class ChatServiceImpl implements ChatService {
    private static final int RAG_TOP_K = 3;
    /**

     * 系统提示：要求仅依据上下文、Markdown 输出。

     */
    public static final String SYSTEM_PROMPT = """
            你是「RAG 企业知识库」的智能助手。请严格根据检索到的上下文回答问题。
            若上下文不足以回答，请明确说明「知识库中未找到相关信息」，不要编造。
            回答请使用清晰的 Markdown（可适当使用标题、列表）。结尾可简要列出依据的文档标题。
            """;

    private final ChatClient chatClient;

    private final VectorStore vectorStore;

    private final ChatSessionMapper chatSessionMapper;

    private final ChatMessageMapper chatMessageMapper;

    private final ObjectMapper objectMapper;

    @Override
    public ChatAskResult ask(Long userId, ChatAskRequest req) throws Exception {
        Long sessionId = req.getSessionId();
        if(sessionId==null){
            ChatSession s = new ChatSession();
            s.setUserId(userId);
            String t = req.getQuestion().trim();
            s.setTitle(t.length() > 30 ? t.substring(0, 30) + "…" : t);
            chatSessionMapper.insert(s);
            sessionId = s.getId();
        }else {
            ChatSession exist = chatSessionMapper.selectById(sessionId);
            if (exist == null || !exist.getUserId().equals(userId)) {
                throw new BusinessException("Session not exist or no permission");
            }
        }
        //检索计时
        long retrievalStart = System.nanoTime();
        List<Document> cited = retrieveForCategories(req.getQuestion(), req.getCategoryIds());
        long retrievalMillis = (System.nanoTime() - retrievalStart) / 1_000_000;
        log.info("RAG retrieval completed: sessionId={}, chunks={}, duration={}ms",
                sessionId, cited.size(), retrievalMillis);

        long llmStart = System.nanoTime();
        String userTurn = buildRagUserMessage(req.getQuestion(), cited);
        String answer = chatClient.prompt().system(SYSTEM_PROMPT).user(userTurn).call().content();
        long llmMs = (System.nanoTime() - llmStart) / 1_000_000L;
        log.info("LLM completed: sessionId={},, duration={}ms",
                sessionId, llmMs);
        List<Map<String, Object>> refs = toRefs(cited);
        String refsJson = objectMapper.writeValueAsString(refs);
        ChatMessage cm = new ChatMessage();
        cm.setSessionId(sessionId);
        cm.setRole("USER");
        cm.setContent(req.getQuestion());
        cm.setRefs(null);

        ChatMessage am = new ChatMessage();
        am.setSessionId(sessionId);
        am.setRole("ASSISTANT");
        am.setContent(answer);
        am.setRefs(refsJson);

        chatMessageMapper.insert(cm);
        chatMessageMapper.insert(am);

        ChatAskResult res = new ChatAskResult();
        res.setSessionId(sessionId);
        res.setAnswer(answer);
        res.setReferences(refs);
        return res;
    }

    /**
     * 有分类时优先限制在指定分类中检索；无命中或 Redis 过滤异常时，回退到全库检索。
     * 分类过滤仅用于提升召回体验，不能作为权限边界。
     */
    private List<Document> retrieveForCategories(String question, List<Long> categoryIds) {
        if (categoryIds == null || categoryIds.isEmpty()) {
            return vectorSimilaritySearch(question, null);
        }

        Set<String> categoryKeys = categoryIds.stream()
                .filter(Objects::nonNull)
                .map(String::valueOf)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        if (categoryKeys.isEmpty()) {
            return vectorSimilaritySearch(question, null);
        }

        try {
            List<Document> filtered = vectorSimilaritySearch(question, buildCategoryIdFilter(categoryKeys));
            if (!filtered.isEmpty()) {
                return filtered;
            }
            log.info("No vector hit for categoryIds {}; falling back to all categories", categoryKeys);
        }
        catch (Exception ex) {
            log.warn("Category-filtered vector search failed; falling back to all categories: {}", ex.toString());
        }
        return vectorSimilaritySearch(question, null);
    }

    private List<Document> vectorSimilaritySearch(String question, Filter.Expression filter) {
        SearchRequest.Builder builder = SearchRequest.builder()
                .query(question)
                .topK(RAG_TOP_K)
                .similarityThreshold(0.0);
        if (filter != null) {
            builder.filterExpression(filter);
        }
        List<Document> documents = vectorStore.similaritySearch(builder.build());
        return documents == null ? Collections.emptyList() : documents;
    }

    private static Filter.Expression buildCategoryIdFilter(Set<String> categoryIds) {
        FilterExpressionBuilder builder = new FilterExpressionBuilder();
        if (categoryIds.size() == 1) {
            return builder.eq("categoryId", categoryIds.iterator().next()).build();
        }
        return builder.in("categoryId", new ArrayList<>(categoryIds)).build();
    }

    /**
     * 将检索到的 chunk 作为上下文拼进用户消息，供模型基于知识库回答。
     */
    private static String buildRagUserMessage(String question, List<Document> cited) {
        String normalizedQuestion = question == null ? "" : question.strip();
        if (cited == null || cited.isEmpty()) {
            return """
                    知识库没有检索到足够相关的片段。请根据系统说明明确告知用户，
                    不要编造知识库中不存在的内容。

                    用户问题：
                    """ + normalizedQuestion;
        }

        StringBuilder prompt = new StringBuilder();
        prompt.append("以下是从知识库检索到的片段。请严格依据这些片段回答；")
                .append("若片段互相冲突，优先采用与问题最直接相关的表述。\n\n");

        int number = 1;
        for (Document document : cited) {
            String text = document.getText();
            if (text == null || text.isBlank()) {
                continue;
            }
            Map<String, Object> metadata = document.getMetadata();
            Object titleValue = metadata == null ? null : metadata.get("title");
            String title = titleValue == null || String.valueOf(titleValue).isBlank()
                    ? "(untitled document)"
                    : String.valueOf(titleValue);

            prompt.append("### 片段 ").append(number++).append(" · ").append(title).append("\n")
                    .append(text.strip()).append("\n\n");
        }

        prompt.append("---\n用户问题：\n").append(normalizedQuestion);
        return prompt.toString();
    }

    /**
     * 将命中的向量 chunk 按检索顺序逐条转为对外引用。
     */
    private static List<Map<String, Object>> toRefs(List<Document> cited) {
        if (cited == null || cited.isEmpty()) {
            return Collections.emptyList();
        }

        List<Map<String, Object>> references = new ArrayList<>();
        for (Document document : cited) {
            Map<String, Object> metadata = document.getMetadata();
            String documentId = metadataValue(metadata, "docId");

            Map<String, Object> reference = new LinkedHashMap<>();
            reference.put("title", defaultIfBlank(metadataValue(metadata, "title"), "(untitled document)"));
            if (documentId != null) {
                reference.put("docId", documentId);
            }
            String categoryId = metadataValue(metadata, "categoryId");
            if (categoryId != null) {
                reference.put("categoryId", categoryId);
            }
            reference.put("snippet", shorten(document.getText(), 256));
            references.add(reference);
        }
        return references;
    }

    private static String metadataValue(Map<String, Object> metadata, String key) {
        if (metadata == null || metadata.get(key) == null) {
            return null;
        }
        String value = String.valueOf(metadata.get(key)).trim();
        return value.isEmpty() ? null : value;
    }

    private static String defaultIfBlank(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private static String shorten(String text, int limit) {
        if (text == null || text.isBlank()) {
            return "";
        }
        String normalized = text.replaceAll("\\s+", " ").trim();
        return normalized.length() <= limit ? normalized : normalized.substring(0, limit) + "…";
    }

    @Override
    public List<ChatSession> listSessions(Long userId) {
        return chatSessionMapper.listByUserId(userId);

    }

    @Override
    public List<ChatMessage> listMessages(Long userId, Long sessionId) {
        ChatSession s = chatSessionMapper.selectById(sessionId);
        if (s == null || !s.getUserId().equals(userId)) {
            throw new BusinessException("Session does not exist or you do not have permission to access it");        }
        return chatMessageMapper.listBySessionId(sessionId);
    }

    @Override
    public void deleteSession(Long userId, Long sessionId) {
        ChatSession s = chatSessionMapper.selectById(sessionId);
        if (s == null || !s.getUserId().equals(userId)) {
            throw new BusinessException("Session does not exist or you do not have permission to access it");
        }
        chatMessageMapper.deleteBySessionId(sessionId);
        chatSessionMapper.deleteById(sessionId);
    }
}
