package com.example.verirag.service.impl;

import com.example.verirag.advisor.RetrievalTimingAdvisor;
import com.example.verirag.dto.ChatAskRequest;
import com.example.verirag.dto.ChatAskResult;
import com.example.verirag.dto.ChatStreamEvent;
import com.example.verirag.entity.ChatMessage;
import com.example.verirag.entity.ChatSession;
import com.example.verirag.exception.BusinessException;
import com.example.verirag.mapper.ChatMessageMapper;
import com.example.verirag.mapper.ChatSessionMapper;
import com.example.verirag.memory.ConversationSummaryService;
import com.example.verirag.observability.RagMetrics;
import com.example.verirag.prompt.RagPromptManager;
import com.example.verirag.prompt.PropertyToolPromptManager;
import com.example.verirag.service.ChatService;
import com.example.verirag.service.RagAnswerCache;
import com.example.verirag.tool.PropertyQueryRouter;
import com.example.verirag.tool.PropertyQueryTools;
import com.example.verirag.tool.ToolCallEventContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.Filter;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.io.InterruptedIOException;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class ChatServiceImpl implements ChatService {
    private static final int DEFAULT_RAG_TOP_K = 8;
    private static final double DEFAULT_SIMILARITY_THRESHOLD = 0.75;
    private static final int DEFAULT_HISTORY_MAX_MESSAGES = 6;
    private static final ZoneId LONDON_TIME_ZONE = ZoneId.of("Europe/London");
    private final ChatClient chatClient;

    @Qualifier("manualHistoryChatClient")
    private final ChatClient manualHistoryChatClient;

    private final VectorStore vectorStore;

    private final ChatSessionMapper chatSessionMapper;

    private final ChatMessageMapper chatMessageMapper;

    private final ObjectMapper objectMapper;

    private final TransactionTemplate transactionTemplate;

    private final RagAnswerCache ragAnswerCache;

    private final RetrievalTimingAdvisor retrievalTimingAdvisor;

    private final RagMetrics ragMetrics;

    private final ConversationSummaryService conversationSummaryService;

    private final RagPromptManager promptManager;

    private final PropertyToolPromptManager propertyToolPromptManager;

    private final PropertyQueryTools propertyQueryTools;

    @Value("${rag.retrieval.similarity-threshold:" + DEFAULT_SIMILARITY_THRESHOLD + "}")
    private double similarityThreshold;

    @Value("${rag.retrieval.top-k:" + DEFAULT_RAG_TOP_K + "}")
    private int retrievalTopK;

    /** 用于检索改写和手动拼接的最近对话消息数量。 */
    @Value("${rag.chat.history-max-messages:" + DEFAULT_HISTORY_MAX_MESSAGES + "}")
    private int historyMaxMessages;

    /** 是否将会话摘要和历史手动压成 user message，而非由 ChatMemory 注入多角色消息。 */
    @Value("${rag.chat.manual-history-enabled:false}")
    private boolean manualHistoryEnabled;

    @Override
    public ChatAskResult ask(Long userId, ChatAskRequest req) throws Exception {
        long requestStart = System.nanoTime();
        Long requestedSessionId = req.getSessionId();
        if (requestedSessionId != null) {
            assertSessionOwner(userId, requestedSessionId);
        }
        List<ChatMessage> history = loadRecentHistory(requestedSessionId);
        boolean structuredPropertyQuery = PropertyQueryRouter
                .isStructuredPropertyQuery(req.getQuestion(), history);

        // 追问的答案依赖前文，不参与跨会话回答缓存，避免上下文错配。
        // 房情随导入变化，结构化查询也不使用跨会话缓存。
        var cached = requestedSessionId == null && !structuredPropertyQuery
                ? ragAnswerCache.find(req.getQuestion(), req.getCategoryIds())
                : Optional.<RagAnswerCache.Hit>empty();
        ragMetrics.recordCache(cached.isPresent());
        if (cached.isPresent()) {
            RagAnswerCache.Hit hit = cached.get();
            String refsJson = objectMapper.writeValueAsString(hit.references());
            Long sessionId = transactionTemplate.execute(status ->
                    persistConversation(userId, requestedSessionId, req.getQuestion(), hit.answer(), refsJson));
            if (sessionId == null) {
                throw new IllegalStateException("Failed to persist cached chat conversation");
            }
            ChatAskResult result = new ChatAskResult();
            result.setSessionId(sessionId);
            result.setAnswer(hit.answer());
            result.setReferences(hit.references());
            ragMetrics.recordRequest(java.time.Duration.ofNanos(System.nanoTime() - requestStart), "cache_hit");
            return result;
        }

        List<Document> cited = retrievalTimingAdvisor.advise(requestedSessionId,
                () -> retrieveKnowledge(
                        req.getQuestion(), history, req.getCategoryIds(),
                        structuredPropertyQuery));

        long llmStart = System.nanoTime();
        String ragContext = buildRagContext(cited);
        String answer;
        try {
            ChatClient.ChatClientRequestSpec prompt = newChatPrompt(requestedSessionId);
            if (structuredPropertyQuery) {
                prompt = prompt.tools(propertyQueryTools);
            }
            answer = prompt
                    .system(structuredPropertyQuery
                            ? buildPropertyToolSystemPrompt(ragContext)
                            : buildModelSystemPrompt(ragContext))
                    .user(structuredPropertyQuery
                            ? buildPropertyToolUserMessage(
                                    req.getQuestion(), requestedSessionId, history)
                            : buildModelUserMessage(req.getQuestion(),
                                    requestedSessionId, history, ragContext))
                    .call()
                    .content();
        }
        catch (RuntimeException ex) {
            ragMetrics.recordLlm(java.time.Duration.ofNanos(System.nanoTime() - llmStart), "sync", "error");
            throw ex;
        }
        long llmMs = (System.nanoTime() - llmStart) / 1_000_000L;
        ragMetrics.recordLlm(java.time.Duration.ofMillis(llmMs), "sync", "success");
        log.info("LLM completed: sessionId={}, duration={}ms", requestedSessionId, llmMs);
        List<Map<String, Object>> refs = toRefs(req.getQuestion(), cited);
        String refsJson = objectMapper.writeValueAsString(refs);
        if (requestedSessionId == null && !structuredPropertyQuery) {
            ragAnswerCache.put(req.getQuestion(), req.getCategoryIds(), answer, refs);
        }

        Long sessionId = transactionTemplate.execute(status ->
                persistConversation(userId, requestedSessionId, req.getQuestion(), answer, refsJson));
        if (sessionId == null) {
            throw new IllegalStateException("Failed to persist chat conversation");
        }
        conversationSummaryService.maybeSummarize(sessionId);

        ChatAskResult res = new ChatAskResult();
        res.setSessionId(sessionId);
        res.setAnswer(answer);
        res.setReferences(refs);
        ragMetrics.recordRequest(java.time.Duration.ofNanos(System.nanoTime() - requestStart), "success");
        return res;
    }

    @Override
    public Flux<ChatStreamEvent> streamAsk(Long userId, ChatAskRequest req) {
        long requestStart = System.nanoTime();
        Long requestedSessionId = req.getSessionId();
        if (requestedSessionId != null) {
            assertSessionOwner(userId, requestedSessionId);
        }
        List<ChatMessage> history = loadRecentHistory(requestedSessionId);
        boolean structuredPropertyQuery = PropertyQueryRouter
                .isStructuredPropertyQuery(req.getQuestion(), history);

        var cached = requestedSessionId == null && !structuredPropertyQuery
                ? ragAnswerCache.find(req.getQuestion(), req.getCategoryIds())
                : Optional.<RagAnswerCache.Hit>empty();
        ragMetrics.recordCache(cached.isPresent());
        if (cached.isPresent()) {
            RagAnswerCache.Hit hit = cached.get();
            Long sessionId = transactionTemplate.execute(status ->
                    persistUserMessage(userId, requestedSessionId, req.getQuestion()));
            if (sessionId == null) {
                return Flux.error(new IllegalStateException("Failed to create chat session"));
            }
            final String refsJson;
            try {
                refsJson = objectMapper.writeValueAsString(hit.references());
            }
            catch (Exception ex) {
                return Flux.error(ex);
            }
            Long finalSessionId = sessionId;
            return Flux.just(ChatStreamEvent.meta(sessionId), ChatStreamEvent.chunk(hit.answer()))
                    .concatWith(Mono.fromCallable(() -> {
                        transactionTemplate.executeWithoutResult(status ->
                                persistAssistantMessage(finalSessionId, hit.answer(), refsJson));
                        conversationSummaryService.maybeSummarize(finalSessionId);
                        return ChatStreamEvent.done(finalSessionId, hit.references());
                    }))
                    .doOnComplete(() -> ragMetrics.recordRequest(
                            java.time.Duration.ofNanos(System.nanoTime() - requestStart), "cache_hit"))
                    .doOnError(error -> ragMetrics.recordRequest(
                            java.time.Duration.ofNanos(System.nanoTime() - requestStart), "error"));
        }

        List<Document> cited = retrievalTimingAdvisor.advise(requestedSessionId,
                () -> retrieveKnowledge(
                        req.getQuestion(), history, req.getCategoryIds(),
                        structuredPropertyQuery));

        List<Map<String, Object>> references = toRefs(req.getQuestion(), cited);
        final String referencesJson;
        try {
            referencesJson = objectMapper.writeValueAsString(references);
        }
        catch (Exception ex) {
            return Flux.error(ex);
        }

        Long sessionId = transactionTemplate.execute(status ->
                persistUserMessage(userId, requestedSessionId, req.getQuestion()));
        if (sessionId == null) {
            return Flux.error(new IllegalStateException("Failed to create chat session"));
        }

        String ragContext = buildRagContext(cited);
        StringBuilder fullAnswer = new StringBuilder();
        long llmStart = System.nanoTime();
        AtomicBoolean firstToken = new AtomicBoolean(true);
        AtomicBoolean streamFailed = new AtomicBoolean(false);
        ChatClient.ChatClientRequestSpec prompt = newChatPrompt(sessionId);
        if (structuredPropertyQuery) {
            prompt = prompt.tools(propertyQueryTools);
        }
        prompt = prompt.system(structuredPropertyQuery
                ? buildPropertyToolSystemPrompt(ragContext)
                : buildModelSystemPrompt(ragContext))
                .user(structuredPropertyQuery
                ? buildPropertyToolUserMessage(
                        req.getQuestion(), requestedSessionId, history)
                : buildModelUserMessage(req.getQuestion(),
                        requestedSessionId, history, ragContext));
        if (structuredPropertyQuery) {
            return streamPropertyToolAnswer(prompt, sessionId, references,
                    referencesJson, llmStart, requestStart);
        }
        return prompt
                .stream().content()
                .doOnNext(chunk -> {
                    fullAnswer.append(chunk);
                    if (firstToken.compareAndSet(true, false)) {
                        long firstTokenMillis = (System.nanoTime() - llmStart) / 1_000_000L;
                        ragMetrics.recordLlmFirstToken(java.time.Duration.ofMillis(firstTokenMillis));
                        log.info("LLM first token: sessionId={}, duration={}ms", sessionId, firstTokenMillis);
                    }
                })
                .doOnComplete(() -> ragMetrics.recordLlm(
                        java.time.Duration.ofNanos(System.nanoTime() - llmStart), "stream", "success"))
                .doOnError(error -> ragMetrics.recordLlm(
                        java.time.Duration.ofNanos(System.nanoTime() - llmStart), "stream", "error"))
                .map(ChatStreamEvent::chunk)
                .startWith(ChatStreamEvent.meta(sessionId))
                .concatWith(Mono.fromCallable(() -> {
                    Long finalSessionId = sessionId;
                    transactionTemplate.executeWithoutResult(status ->
                            persistAssistantMessage(finalSessionId, fullAnswer.toString(), referencesJson));
                    conversationSummaryService.maybeSummarize(finalSessionId);
                    if (requestedSessionId == null && !structuredPropertyQuery) {
                        ragAnswerCache.put(req.getQuestion(), req.getCategoryIds(), fullAnswer.toString(), references);
                    }
                    long llmMillis = (System.nanoTime() - llmStart) / 1_000_000;
                    log.info("LLM streaming completed: sessionId={}, duration={}ms", finalSessionId, llmMillis);
                    return ChatStreamEvent.done(finalSessionId, references);
                }))
                .onErrorResume(error -> {
                    streamFailed.set(true);
                    String message = friendlyStreamError(error);
                    log.warn("LLM streaming interrupted: sessionId={}, partialChars={}, error={}",
                            sessionId, fullAnswer.length(), error.toString());
                    return Mono.fromCallable(() -> {
                        String partialAnswer = fullAnswer.toString().strip();
                        String persistedAnswer = partialAnswer.isBlank()
                                ? "> " + message
                                : partialAnswer + "\n\n> " + message;
                        transactionTemplate.executeWithoutResult(status ->
                                persistAssistantMessage(sessionId, persistedAnswer, referencesJson));
                        return ChatStreamEvent.error(sessionId, message);
                    });
                })
                .doOnComplete(() -> ragMetrics.recordRequest(
                        java.time.Duration.ofNanos(System.nanoTime() - requestStart),
                        streamFailed.get() ? "stream_error" : "success"))
                .doOnError(error -> ragMetrics.recordRequest(
                        java.time.Duration.ofNanos(System.nanoTime() - requestStart), "error"));
    }

    /**
     * Tool calling uses a non-streaming model request because some OpenAI-compatible
     * providers omit the function name in continuation chunks. The outer API remains
     * SSE and publishes local tool progress events while the blocking call runs.
     */
    private Flux<ChatStreamEvent> streamPropertyToolAnswer(
            ChatClient.ChatClientRequestSpec prompt,
            Long sessionId,
            List<Map<String, Object>> references,
            String referencesJson,
            long llmStart,
            long requestStart) {
        AtomicBoolean failed = new AtomicBoolean(false);
        return Flux.deferContextual(contextView -> Flux.<ChatStreamEvent>create(sink -> {
            sink.next(ChatStreamEvent.meta(sessionId));
            var task = Mono.fromRunnable(() -> {
                try {
                    String answer = ToolCallEventContext.withListener(
                            event -> {
                                if (!sink.isCancelled()) {
                                    sink.next(toToolProgressEvent(event));
                                }
                            },
                            () -> prompt.call().content());
                    long llmMillis = (System.nanoTime() - llmStart) / 1_000_000L;
                    ragMetrics.recordLlmFirstToken(java.time.Duration.ofMillis(llmMillis));
                    ragMetrics.recordLlm(java.time.Duration.ofMillis(llmMillis),
                            "sync_tool", "success");
                    log.info("LLM tool response completed: sessionId={}, duration={}ms",
                            sessionId, llmMillis);
                    if (sink.isCancelled()) {
                        return;
                    }
                    sink.next(ChatStreamEvent.chunk(answer));
                    transactionTemplate.executeWithoutResult(status ->
                            persistAssistantMessage(sessionId, answer, referencesJson));
                    conversationSummaryService.maybeSummarize(sessionId);
                    sink.next(ChatStreamEvent.done(sessionId, references));
                    sink.complete();
                }
                catch (RuntimeException error) {
                    failed.set(true);
                    ragMetrics.recordLlm(
                            java.time.Duration.ofNanos(System.nanoTime() - llmStart),
                            "sync_tool", "error");
                    String message = friendlyStreamError(error);
                    log.warn("LLM tool response interrupted: sessionId={}, error={}",
                            sessionId, error.toString());
                    if (sink.isCancelled()) {
                        return;
                    }
                    transactionTemplate.executeWithoutResult(status ->
                            persistAssistantMessage(sessionId, "> " + message, referencesJson));
                    sink.next(ChatStreamEvent.error(sessionId, message));
                    sink.complete();
                }
            }).subscribeOn(Schedulers.boundedElastic())
                    .contextWrite(contextView)
                    .subscribe();
            sink.onCancel(task::dispose);
        }))
                .doOnComplete(() -> ragMetrics.recordRequest(
                        java.time.Duration.ofNanos(System.nanoTime() - requestStart),
                        failed.get() ? "stream_error" : "success"))
                .doOnError(error -> ragMetrics.recordRequest(
                        java.time.Duration.ofNanos(System.nanoTime() - requestStart), "error"));
    }

    private static ChatStreamEvent toToolProgressEvent(ToolCallEventContext.Event event) {
        String label = switch (event.toolName()) {
            case "search_room_offers" -> "房源、报价和库存";
            case "quote_room_offer" -> "指定房型报价";
            case "list_residences" -> "公寓地址";
            case "get_inventory_summary" -> "公寓和库存统计";
            default -> "房源数据";
        };
        return switch (event.phase()) {
            case STARTED -> ChatStreamEvent.toolStart(
                    event.toolName(), "正在查询" + label + "…");
            case COMPLETED -> ChatStreamEvent.toolDone(
                    event.toolName(), label + "查询完成，正在整理回答…");
            case FAILED -> ChatStreamEvent.toolError(
                    event.toolName(), label + "查询失败，正在处理错误…");
        };
    }

    static String friendlyStreamError(Throwable error) {
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (cause instanceof InterruptedIOException
                    || (cause.getMessage() != null
                    && cause.getMessage().toLowerCase(Locale.ROOT).contains("timeout"))) {
                return "模型响应超时，已保留当前生成内容，请重试。";
            }
        }
        return "模型响应中断，已保留当前生成内容，请重试。";
    }

    /** 持久化发生在一个短事务内，避免在 LLM 调用期间长时间占用数据库事务。 */
    private Long persistConversation(Long userId, Long requestedSessionId, String question, String answer, String refsJson) {
        Long sessionId = requestedSessionId;
        if (sessionId == null) {
            ChatSession session = new ChatSession();
            session.setUserId(userId);
            String title = question.trim();
            session.setTitle(title.length() > 30 ? title.substring(0, 30) + "…" : title);
            chatSessionMapper.insert(session);
            sessionId = session.getId();
        }
        else {
            // 在写消息前再次校验，避免会话在模型生成期间被其他请求删除。
            assertSessionOwner(userId, sessionId);
        }

        ChatMessage cm = new ChatMessage();
        cm.setSessionId(sessionId);
        cm.setRole("USER");
        cm.setContent(question);
        cm.setRefs(null);

        ChatMessage am = new ChatMessage();
        am.setSessionId(sessionId);
        am.setRole("ASSISTANT");
        am.setContent(answer);
        am.setRefs(refsJson);

        chatMessageMapper.insert(cm);
        chatMessageMapper.insert(am);
        chatSessionMapper.touchUpdateTime(sessionId);
        return sessionId;
    }

    /** 在流开始前以短事务创建/校验会话，并持久化用户消息。 */
    private Long persistUserMessage(Long userId, Long requestedSessionId, String question) {
        Long sessionId = requestedSessionId;
        if (sessionId == null) {
            ChatSession session = new ChatSession();
            session.setUserId(userId);
            String title = question.trim();
            session.setTitle(title.length() > 30 ? title.substring(0, 30) + "…" : title);
            chatSessionMapper.insert(session);
            sessionId = session.getId();
        }
        else {
            assertSessionOwner(userId, sessionId);
        }
        ChatMessage message = new ChatMessage();
        message.setSessionId(sessionId);
        message.setRole("USER");
        message.setContent(question);
        chatMessageMapper.insert(message);
        chatSessionMapper.touchUpdateTime(sessionId);
        return sessionId;
    }

    /** 流正常完成后，以独立短事务持久化完整回答与引用。 */
    private void persistAssistantMessage(Long sessionId, String answer, String refsJson) {
        ChatMessage message = new ChatMessage();
        message.setSessionId(sessionId);
        message.setRole("ASSISTANT");
        message.setContent(answer);
        message.setRefs(refsJson);
        chatMessageMapper.insert(message);
        chatSessionMapper.touchUpdateTime(sessionId);
    }

    private void assertSessionOwner(Long userId, Long sessionId) {
        ChatSession session = chatSessionMapper.selectById(sessionId);
        if (session == null || !session.getUserId().equals(userId)) {
            throw new BusinessException("Session does not exist or you do not have permission to access it");
        }
    }

    /** 房源查询同时检索静态知识并调用数据库 Tool；普通问答保持原 RAG 流程。 */
    private List<Document> retrieveKnowledge(String question, List<ChatMessage> history,
                                             List<Long> categoryIds,
                                             boolean structuredPropertyQuery) {
        String query = buildRetrievalQuery(question, history);
        if (structuredPropertyQuery) {
            query += "\n检索公寓的地址、区域、交通、设施、附近学校和周边地点等静态资料。"
                    + "报价、租期和库存由数据库 Tool 提供。";
        }
        List<Document> documents = retrieveForCategories(query, categoryIds);
        if (!structuredPropertyQuery) {
            return documents;
        }
        List<Document> staticKnowledge = keepStaticPropertyKnowledge(documents);
        log.info("Hybrid property RAG: retrieved={}, staticKnowledge={}",
                documents.size(), staticKnowledge.size());
        return staticKnowledge;
    }

    /**
     * 结构化房源问答只把静态公寓资料交给模型。旧 Excel 房型向量包含 roomType
     * 元数据，报价和库存已经由数据库 Tool 接管，避免两个来源互相冲突。
     */
    static List<Document> keepStaticPropertyKnowledge(List<Document> documents) {
        if (documents == null || documents.isEmpty()) {
            return Collections.emptyList();
        }
        return documents.stream()
                .filter(Objects::nonNull)
                .filter(document -> metadataValue(document.getMetadata(), "roomType") == null)
                .toList();
    }

    /**
     * 有分类时只在指定分类中检索；无足够相关命中时直接返回空结果。
     * 不将其它分类的低相关片段作为兜底上下文，避免模型产生无依据引用。
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
            List<Document> filtered = vectorSimilaritySearch(
                    question, buildCategoryIdFilter(categoryKeys));
            if (!filtered.isEmpty()) {
                return filtered;
            }
            log.info("No sufficiently relevant vector hit for categoryIds {}; returning no context", categoryKeys);
        }
        catch (Exception ex) {
            log.warn("Category-filtered vector search failed; returning no context: {}", ex.toString());
        }
        return Collections.emptyList();
    }

    private List<Document> vectorSimilaritySearch(String question, Filter.Expression filter) {
        SearchRequest.Builder builder = SearchRequest.builder()
                .query(question)
                .topK(retrievalTopK)
                .similarityThreshold(similarityThreshold);
        if (filter != null) {
            builder.filterExpression(filter);
        }
        List<Document> documents = vectorStore.similaritySearch(builder.build());
        if (documents == null || documents.isEmpty()) {
            return Collections.emptyList();
        }
        // Redis KNN 通常已经按距离排序；这里显式按 Spring AI 归一化后的 score
        // 降序再排一次，确保上下文和对外 references 均按相关性从高到低展示。
        return documents.stream()
                .filter(document -> document.getScore() != null
                        && document.getScore() >= similarityThreshold)
                .sorted(Comparator.comparing(Document::getScore,
                        Comparator.nullsLast(Comparator.reverseOrder())))
                .toList();
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
    private List<ChatMessage> loadRecentHistory(Long sessionId) {
        if (sessionId == null) {
            return Collections.emptyList();
        }
        List<ChatMessage> messages = chatMessageMapper.listBySessionId(sessionId);
        if (messages == null || messages.isEmpty()) {
            return Collections.emptyList();
        }
        int from = Math.max(messages.size() - Math.max(historyMaxMessages, 0), 0);
        return messages.subList(from, messages.size());
    }

    /** 用上一轮用户问题补足“这个/它/哪里”等指代不明的追问检索语义。 */
    static String buildRetrievalQuery(String question, List<ChatMessage> history) {
        String query = question;
        if (history != null) {
            for (int i = history.size() - 1; i >= 0; i--) {
                ChatMessage message = history.get(i);
                if ("USER".equals(message.getRole()) && message.getContent() != null && !message.getContent().isBlank()) {
                    query = message.getContent().strip() + "\n后续问题：" + question.strip();
                    break;
                }
            }
        }
        return query;
    }

    /**
     * 常规模型交给 ChatMemory Advisor 注入原生 role 历史；兼容性模式则只使用无 Advisor 的客户端。
     */
    private ChatClient.ChatClientRequestSpec newChatPrompt(Long conversationId) {
        if (manualHistoryEnabled) {
            return manualHistoryChatClient.prompt();
        }
        return chatClient.prompt()
                .advisors(advisors -> advisors.param(ChatMemory.CONVERSATION_ID, String.valueOf(conversationId)));
    }

    private String buildModelSystemPrompt(String ragContext) {
        // 手动模式的知识库和历史都被收敛到 user message，最终请求严格只有 system + user。
        return manualHistoryEnabled ? promptManager.systemPrompt() : promptManager.systemPrompt() + "\n\n" + ragContext;
    }

    private String buildPropertyToolSystemPrompt(String ragContext) {
        return propertyToolPromptManager.systemPrompt()
                + "\n\n当前日期（Europe/London）：" + LocalDate.now(LONDON_TIME_ZONE)
                + "\n\n## 知识库静态资料\n\n" + ragContext;
    }

    private String buildPropertyToolUserMessage(String question, Long sessionId,
                                                List<ChatMessage> history) {
        if (!manualHistoryEnabled) {
            return question;
        }
        StringBuilder input = new StringBuilder();
        ChatSession session = sessionId == null ? null : chatSessionMapper.selectById(sessionId);
        if (session != null && session.getMemorySummary() != null
                && !session.getMemorySummary().isBlank()) {
            input.append("【较早对话摘要】\n")
                    .append(session.getMemorySummary().strip())
                    .append("\n\n");
        }
        if (history != null && !history.isEmpty()) {
            input.append("【最近对话记录】\n");
            for (ChatMessage message : history) {
                if (message.getContent() == null || message.getContent().isBlank()) {
                    continue;
                }
                input.append("ASSISTANT".equals(message.getRole()) ? "助手：" : "用户：")
                        .append(message.getContent().strip())
                        .append("\n");
            }
            input.append("\n");
        }
        return input.append("【本次问题】\n").append(question.strip()).toString();
    }

    private String buildModelUserMessage(String question, Long sessionId, List<ChatMessage> history, String ragContext) {
        if (!manualHistoryEnabled) {
            return question;
        }

        StringBuilder input = new StringBuilder();
        ChatSession session = sessionId == null ? null : chatSessionMapper.selectById(sessionId);
        if (session != null && session.getMemorySummary() != null && !session.getMemorySummary().isBlank()) {
            input.append("【较早对话摘要】\n")
                    .append(session.getMemorySummary().strip())
                    .append("\n\n");
        }
        if (history != null && !history.isEmpty()) {
            input.append("【最近对话记录】\n");
            for (ChatMessage message : history) {
                String content = message.getContent();
                if (content == null || content.isBlank()) {
                    continue;
                }
                String speaker = "ASSISTANT".equals(message.getRole()) ? "助手" : "用户";
                input.append(speaker).append("：").append(content.strip()).append("\n");
            }
            input.append("\n");
        }
        input.append("【知识库资料】\n")
                .append(ragContext.strip())
                .append("\n\n【本次问题】\n")
                .append(question.strip());
        return input.toString();
    }

    private String buildRagContext(List<Document> cited) {
        if (cited == null || cited.isEmpty()) {
            return promptManager.noContext();
        }

        StringBuilder prompt = new StringBuilder();
        prompt.append(promptManager.contextPrefix()).append("\n\n");

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

            prompt.append(promptManager.contextItem(number++, title, text.strip())).append("\n\n");
        }

        return prompt.toString();
    }

    /**
     * 将命中的向量 chunk 按检索顺序逐条转为对外引用。
     */
    private List<Map<String, Object>> toRefs(String question, List<Document> cited) {
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
            // 每一个引用都对应一个按相关性排序的命中 Chunk；保留全文，供前端展开查看。
            reference.put("content", document.getText() == null ? "" : document.getText().strip());
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
