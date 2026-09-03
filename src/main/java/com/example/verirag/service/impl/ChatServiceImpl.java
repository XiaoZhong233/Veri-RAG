package com.example.verirag.service.impl;

import com.example.verirag.advisor.RetrievalTimingAdvisor;
import com.example.verirag.advisor.PropertyResponseAdvisor;
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
import com.example.verirag.prompt.SalesRecommendationPromptManager;
import com.example.verirag.service.ChatService;
import com.example.verirag.service.RagAnswerCache;
import com.example.verirag.tool.PropertyQueryIntent;
import com.example.verirag.tool.PropertyIntentClassifier;
import com.example.verirag.tool.PropertyPriceGuard;
import com.example.verirag.tool.PropertyToolSelector;
import com.example.verirag.tool.ToolCallEventContext;
import com.example.verirag.tool.PropertyToolFallbackFormatter;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.document.Document;
import org.springframework.ai.openai.OpenAiChatOptions;
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
import java.time.Duration;
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
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class ChatServiceImpl implements ChatService {
    private static final int DEFAULT_RAG_TOP_K = 4;
    private static final int PROPERTY_CANDIDATE_RETRIEVAL_TOP_K = 30;
    private static final int PROPERTY_MAX_DISTINCT_RESIDENCES = 12;
    private static final Pattern RESIDENCE_NAME_PREFIX =
            Pattern.compile("(?m)^公寓名称：\\s*(.+?)\\s*$");
    private static final Pattern RESIDENCE_H2 =
            Pattern.compile("(?m)^##\\s+(.+?)\\s*$");
    private static final double DEFAULT_SIMILARITY_THRESHOLD = 0.75;
    private static final int DEFAULT_HISTORY_MAX_MESSAGES = 6;
    private static final ZoneId LONDON_TIME_ZONE = ZoneId.of("Europe/London");
    private static final String PLAIN_TEXT_OUTPUT_INSTRUCTION = """

            当前回答将发送到不支持 Markdown 的纯文本聊天窗口。最终回答必须使用易读的纯文本：
            不要输出 Markdown 标题、表格、加粗、链接语法、代码围栏或引用符号；
            即使前文要求使用 Markdown 表格，也以本条为准，改为数字序号和换行；
            每个房源单独编号，各字段采用“字段：内容”的形式，链接直接输出完整 URL。
            """;
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

    private final SalesRecommendationPromptManager salesRecommendationPromptManager;

    private final PropertyToolSelector propertyToolSelector;

    private final PropertyIntentClassifier propertyIntentClassifier;

    private final PropertyPriceGuard propertyPriceGuard;

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

    /** 主回答生成上限；独立于15秒的轻量意图分类超时。 */
    @Value("${rag.chat.response-timeout:3m}")
    private Duration llmResponseTimeout;

    @Value("${rag.chat.property-max-tokens:600}")
    private int propertyMaxTokens;

    @Value("${rag.chat.property-thinking-enabled:true}")
    private boolean propertyThinkingEnabled;

    @Override
    public ChatAskResult ask(Long userId, ChatAskRequest req) throws Exception {
        long requestStart = System.nanoTime();
        Long requestedSessionId = req.getSessionId();
        if (requestedSessionId != null) {
            assertSessionOwner(userId, requestedSessionId);
        }
        List<ChatMessage> history = loadRecentHistory(requestedSessionId);
        PropertyQueryIntent propertyIntent = propertyIntentClassifier
                .resolve(req.getQuestion(), history);
        if (propertyIntent == PropertyQueryIntent.ACKNOWLEDGE) {
            return completeAcknowledgement(
                    userId, requestedSessionId, req.getQuestion(), requestStart);
        }
        boolean structuredPropertyQuery = propertyIntent.structured();
        boolean propertyHandled = propertyIntent.propertyHandled();

        // 追问的答案依赖前文，不参与跨会话回答缓存，避免上下文错配。
        // 房情随导入变化，结构化查询也不使用跨会话缓存。
        var cached = requestedSessionId == null && !propertyHandled && !req.isPlainText()
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
                        propertyIntent));

        long llmStart = System.nanoTime();
        String ragContext = buildRagContext(cited);
        String rawAnswer;
        boolean fallbackUsed = false;
        AtomicReference<ToolCallEventContext.Event> completedTool = new AtomicReference<>();
        try {
            ChatClient.ChatClientRequestSpec prompt = newChatPrompt(requestedSessionId);
            if (structuredPropertyQuery) {
                prompt = prompt.tools(
                        (Object[]) propertyToolSelector.callbacksFor(propertyIntent));
                log.info("Property Tool selected: intent={}, tool={}",
                        propertyIntent, propertyIntent.toolName());
            }
            prompt = prompt.system(propertyHandled
                            ? buildPropertyToolSystemPrompt(
                                    propertyIntent, req.getQuestion(), req.isPlainText())
                            : buildModelSystemPrompt(ragContext, req.isPlainText()))
                    .user(propertyHandled
                            ? buildPropertyToolUserMessage(
                                    req.getQuestion(), requestedSessionId, history)
                            : buildModelUserMessage(req.getQuestion(),
                                    requestedSessionId, history, ragContext));
            prompt = withModelOptions(prompt, propertyHandled);
            boolean protectPrice = priceRestricted(propertyIntent)
                    || propertyPriceGuard.shouldProtect(req.getQuestion());
            prompt = enablePropertyResponseGuard(prompt, protectPrice, req.getQuestion());
            ChatClient.ChatClientRequestSpec finalPrompt = prompt;
            rawAnswer = callModelWithTimeout(() -> structuredPropertyQuery
                    ? ToolCallEventContext.withListener(event -> {
                        if (event.phase() == ToolCallEventContext.Phase.COMPLETED) {
                            completedTool.set(event);
                        }
                    }, () -> finalPrompt.call().content())
                    : finalPrompt.call().content());
        }
        catch (RuntimeException ex) {
            if (isModelTimeout(ex) && completedTool.get() != null) {
                rawAnswer = PropertyToolFallbackFormatter.format(
                        req.getQuestion(), completedTool.get());
                fallbackUsed = true;
                log.warn("LLM timed out after Tool completion; using deterministic fallback: "
                                + "sessionId={}, tool={}", requestedSessionId,
                        completedTool.get().toolName());
            }
            else {
                ragMetrics.recordLlm(java.time.Duration.ofNanos(System.nanoTime() - llmStart),
                        "sync", "error");
                if (isModelTimeout(ex)) {
                    throw new BusinessException(504, modelTimeoutMessage(req.getQuestion()));
                }
                throw ex;
            }
        }
        String answer = rawAnswer;
        long llmMs = (System.nanoTime() - llmStart) / 1_000_000L;
        ragMetrics.recordLlm(java.time.Duration.ofMillis(llmMs),
                fallbackUsed ? "sync_tool_fallback" : "sync", "success");
        log.info("LLM completed: sessionId={}, duration={}ms", requestedSessionId, llmMs);
        List<Map<String, Object>> refs = toRefs(req.getQuestion(), cited);
        String refsJson = objectMapper.writeValueAsString(refs);
        if (requestedSessionId == null && !propertyHandled && !req.isPlainText()) {
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
        Long sessionId = transactionTemplate.execute(status ->
                persistUserMessage(userId, requestedSessionId, req.getQuestion()));
        if (sessionId == null) {
            return Flux.error(new IllegalStateException("Failed to create chat session"));
        }

        Flux<ChatStreamEvent> progress = Flux.just(
                ChatStreamEvent.meta(sessionId),
                ChatStreamEvent.progress("intent_start", "正在识别您的需求…"));
        Flux<ChatStreamEvent> classified = Mono.fromCallable(() ->
                        propertyIntentClassifier.resolve(req.getQuestion(), history))
                .subscribeOn(Schedulers.boundedElastic())
                .flatMapMany(propertyIntent -> continueStreamAsk(
                        req, requestedSessionId, history, propertyIntent,
                        sessionId, requestStart));
        return progress.concatWith(classified)
                .onErrorResume(error -> {
                    String message = friendlyStreamError(error, req.getQuestion());
                    log.warn("Streaming request preparation failed: sessionId={}, error={}",
                            sessionId, error.toString());
                    return Flux.just(ChatStreamEvent.error(sessionId, message));
                });
    }

    private Flux<ChatStreamEvent> continueStreamAsk(
            ChatAskRequest req,
            Long requestedSessionId,
            List<ChatMessage> history,
            PropertyQueryIntent propertyIntent,
            Long sessionId,
            long requestStart) {
        boolean structuredPropertyQuery = propertyIntent.structured();
        boolean propertyHandled = propertyIntent.propertyHandled();
        ChatStreamEvent intentDone = ChatStreamEvent.intentDone(
                propertyIntent.name(), intentProgressMessage(propertyIntent));
        if (propertyIntent == PropertyQueryIntent.ACKNOWLEDGE) {
            return streamAcknowledgement(
                    req.getQuestion(), sessionId, intentDone, requestStart);
        }
        ChatStreamEvent routeStart = ChatStreamEvent.progress(
                "route_start", structuredPropertyQuery
                        ? "正在准备调用" + propertyIntentLabel(propertyIntent) + "工具…"
                        : propertyHandled
                        ? "正在整理" + propertyIntentLabel(propertyIntent) + "答复…"
                        : "正在检索知识库资料…");

        var cached = requestedSessionId == null && !propertyHandled && !req.isPlainText()
                ? ragAnswerCache.find(req.getQuestion(), req.getCategoryIds())
                : Optional.<RagAnswerCache.Hit>empty();
        ragMetrics.recordCache(cached.isPresent());
        if (cached.isPresent()) {
            RagAnswerCache.Hit hit = cached.get();
            final String refsJson;
            try {
                refsJson = objectMapper.writeValueAsString(hit.references());
            }
            catch (Exception ex) {
                return Flux.error(ex);
            }
            Long finalSessionId = sessionId;
            return Flux.just(
                            intentDone,
                            ChatStreamEvent.progress("route_start",
                                    "已命中回答缓存，正在返回结果…"),
                            ChatStreamEvent.chunk(hit.answer()))
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

        return Flux.just(intentDone, routeStart)
                .concatWith(Flux.defer(() -> prepareStreamAnswer(
                        req, requestedSessionId, history, propertyIntent,
                        sessionId, requestStart)));
    }

    private Flux<ChatStreamEvent> prepareStreamAnswer(
            ChatAskRequest req,
            Long requestedSessionId,
            List<ChatMessage> history,
            PropertyQueryIntent propertyIntent,
            Long sessionId,
            long requestStart) {
        boolean structuredPropertyQuery = propertyIntent.structured();
        boolean propertyHandled = propertyIntent.propertyHandled();
        List<Document> cited = retrievalTimingAdvisor.advise(requestedSessionId,
                () -> retrieveKnowledge(
                        req.getQuestion(), history, req.getCategoryIds(),
                        propertyIntent));

        List<Map<String, Object>> references = toRefs(req.getQuestion(), cited);
        final String referencesJson;
        try {
            referencesJson = objectMapper.writeValueAsString(references);
        }
        catch (Exception ex) {
            return Flux.error(ex);
        }

        String ragContext = buildRagContext(cited);
        StringBuilder fullAnswer = new StringBuilder();
        long llmStart = System.nanoTime();
        AtomicBoolean firstToken = new AtomicBoolean(true);
        AtomicBoolean streamFailed = new AtomicBoolean(false);
        ChatClient.ChatClientRequestSpec prompt = newChatPrompt(sessionId);
        if (structuredPropertyQuery) {
            prompt = prompt.tools(
                    (Object[]) propertyToolSelector.callbacksFor(propertyIntent));
            log.info("Property Tool selected: intent={}, tool={}",
                    propertyIntent, propertyIntent.toolName());
        }
        prompt = prompt.system(propertyHandled
                ? buildPropertyToolSystemPrompt(
                        propertyIntent, req.getQuestion(), req.isPlainText())
                : buildModelSystemPrompt(ragContext, req.isPlainText()))
                .user(propertyHandled
                ? buildPropertyToolUserMessage(
                        req.getQuestion(), requestedSessionId, history)
                : buildModelUserMessage(req.getQuestion(),
                        requestedSessionId, history, ragContext));
        prompt = withModelOptions(prompt, propertyHandled);
        boolean protectPrice = priceRestricted(propertyIntent)
                || propertyPriceGuard.shouldProtect(req.getQuestion());
        if (propertyHandled || protectPrice) {
            prompt = enablePropertyResponseGuard(prompt, protectPrice, req.getQuestion());
            return streamBufferedAnswer(prompt, sessionId,
                    references, referencesJson, req.getQuestion(), llmStart, requestStart);
        }
        return prompt
                .stream().content()
                .timeout(llmResponseTimeout)
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
                .concatWith(Mono.fromCallable(() -> {
                    Long finalSessionId = sessionId;
                    transactionTemplate.executeWithoutResult(status ->
                            persistAssistantMessage(finalSessionId, fullAnswer.toString(), referencesJson));
                    conversationSummaryService.maybeSummarize(finalSessionId);
                    if (requestedSessionId == null && !propertyHandled && !req.isPlainText()) {
                        ragAnswerCache.put(req.getQuestion(), req.getCategoryIds(), fullAnswer.toString(), references);
                    }
                    long llmMillis = (System.nanoTime() - llmStart) / 1_000_000;
                    log.info("LLM streaming completed: sessionId={}, duration={}ms", finalSessionId, llmMillis);
                    return ChatStreamEvent.done(finalSessionId, references);
                }))
                .onErrorResume(error -> {
                    streamFailed.set(true);
                    String message = friendlyStreamError(error, req.getQuestion());
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

    private static String intentProgressMessage(PropertyQueryIntent intent) {
        if (intent == PropertyQueryIntent.ACKNOWLEDGE) {
            return "已识别为对话确认。";
        }
        return intent.propertyHandled()
                ? "已识别为" + propertyIntentLabel(intent) + "需求。"
                : "已识别为知识库问答。";
    }

    private static String propertyIntentLabel(PropertyQueryIntent intent) {
        return switch (intent) {
            case ACKNOWLEDGE -> "对话确认";
            case CLARIFY -> "房源咨询意图澄清";
            case GUIDANCE -> "房源咨询补充条件";
            case RESTRICTED -> "受限房源咨询";
            case RECOMMEND -> "房源推荐";
            case QUOTE -> "指定房型可订性核验";
            case DETAIL -> "公寓详情";
            case LIST -> "公寓列表";
            case SUMMARY -> "库存汇总";
            case NONE -> "知识库查询";
        };
    }

    /**
     * Tool calling uses a non-streaming model request because some OpenAI-compatible
     * providers omit the function name in continuation chunks. The outer API remains
     * SSE and publishes local tool progress events while the blocking call runs.
     */
    private Flux<ChatStreamEvent> streamBufferedAnswer(
            ChatClient.ChatClientRequestSpec prompt,
            Long sessionId,
            List<Map<String, Object>> references,
            String referencesJson,
            String question,
            long llmStart,
            long requestStart) {
        AtomicBoolean failed = new AtomicBoolean(false);
        AtomicReference<ToolCallEventContext.Event> completedTool = new AtomicReference<>();
        return Flux.deferContextual(contextView -> Flux.<ChatStreamEvent>create(sink -> {
            var task = Mono.fromRunnable(() -> {
                try {
                    String answer = callModelWithTimeout(() ->
                            ToolCallEventContext.withListener(
                                    event -> {
                                        if (event.phase() == ToolCallEventContext.Phase.COMPLETED) {
                                            completedTool.set(event);
                                        }
                                        if (!sink.isCancelled()) {
                                            sink.next(toToolProgressEvent(event));
                                        }
                                    },
                                    () -> prompt.call().content()));
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
                    if (isModelTimeout(error) && completedTool.get() != null) {
                        String answer = PropertyToolFallbackFormatter.format(
                                question, completedTool.get());
                        long llmMillis = (System.nanoTime() - llmStart) / 1_000_000L;
                        ragMetrics.recordLlm(java.time.Duration.ofMillis(llmMillis),
                                "sync_tool_fallback", "success");
                        log.warn("LLM timed out after Tool completion; using deterministic "
                                        + "fallback: sessionId={}, tool={}",
                                sessionId, completedTool.get().toolName());
                        if (sink.isCancelled()) {
                            return;
                        }
                        sink.next(ChatStreamEvent.chunk(answer));
                        transactionTemplate.executeWithoutResult(status ->
                                persistAssistantMessage(sessionId, answer, referencesJson));
                        conversationSummaryService.maybeSummarize(sessionId);
                        sink.next(ChatStreamEvent.done(sessionId, references));
                        sink.complete();
                        return;
                    }
                    failed.set(true);
                    ragMetrics.recordLlm(
                            java.time.Duration.ofNanos(System.nanoTime() - llmStart),
                            "sync_tool", "error");
                    String message = friendlyStreamError(error, question);
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
            case "search_room_offers" -> "房源和库存";
            case "check_room_offer_availability" -> "指定房型可订状态";
            case "get_residence_details" -> "公寓设施和周边详情";
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

    private static boolean priceRestricted(PropertyQueryIntent intent) {
        return intent != null && intent.propertyHandled();
    }

    private ChatAskResult completeAcknowledgement(
            Long userId, Long requestedSessionId, String question, long requestStart) throws Exception {
        String answer = acknowledgementAnswer(question);
        List<Map<String, Object>> references = List.of();
        Long sessionId = transactionTemplate.execute(status ->
                persistConversation(userId, requestedSessionId, question, answer, "[]"));
        if (sessionId == null) {
            throw new IllegalStateException("Failed to persist acknowledgement conversation");
        }
        conversationSummaryService.maybeSummarize(sessionId);
        ragMetrics.recordCache(false);
        ragMetrics.recordRequest(java.time.Duration.ofNanos(System.nanoTime() - requestStart),
                "acknowledgement");
        ChatAskResult result = new ChatAskResult();
        result.setSessionId(sessionId);
        result.setAnswer(answer);
        result.setReferences(references);
        return result;
    }

    private Flux<ChatStreamEvent> streamAcknowledgement(
            String question, Long sessionId, ChatStreamEvent intentDone, long requestStart) {
        String answer = acknowledgementAnswer(question);
        return Flux.just(
                        intentDone,
                        ChatStreamEvent.progress("route_start", "正在回复…"),
                        ChatStreamEvent.chunk(answer))
                .concatWith(Mono.fromCallable(() -> {
                    transactionTemplate.executeWithoutResult(status ->
                            persistAssistantMessage(sessionId, answer, "[]"));
                    conversationSummaryService.maybeSummarize(sessionId);
                    ragMetrics.recordCache(false);
                    ragMetrics.recordRequest(
                            java.time.Duration.ofNanos(System.nanoTime() - requestStart),
                            "acknowledgement");
                    return ChatStreamEvent.done(sessionId, List.of());
                }));
    }

    static String acknowledgementAnswer(String question) {
        boolean chinese = Objects.toString(question, "").codePoints().anyMatch(codePoint ->
                Character.UnicodeScript.of(codePoint) == Character.UnicodeScript.HAN);
        return chinese
                ? "好的，有需要随时告诉我。"
                : "Of course. Let me know whenever you need anything else.";
    }

    private static ChatClient.ChatClientRequestSpec enablePropertyResponseGuard(
            ChatClient.ChatClientRequestSpec prompt, boolean enabled, String question) {
        if (!enabled) {
            return prompt;
        }
        return prompt.advisors(advisors -> advisors
                .param(PropertyResponseAdvisor.ENABLED, true)
                .param(PropertyResponseAdvisor.QUESTION, Objects.toString(question, "")));
    }

    private ChatClient.ChatClientRequestSpec withModelOptions(
            ChatClient.ChatClientRequestSpec prompt, boolean propertyHandled) {
        var options = OpenAiChatOptions.builder()
                .timeout(llmResponseTimeout);
        if (propertyHandled) {
            options.maxTokens(Math.max(propertyMaxTokens, 1));
            if (!propertyThinkingEnabled) {
                options.extraBody(Map.of("enable_thinking", false));
            }
        }
        return prompt.options(options);
    }

    /**
     * 对包含多轮 Tool Calling 的同步调用增加端到端看门狗；超时会取消 boundedElastic 任务并中断底层调用。
     */
    private String callModelWithTimeout(Supplier<String> invocation) {
        return Mono.fromCallable(invocation::get)
                .subscribeOn(Schedulers.boundedElastic())
                .timeout(llmResponseTimeout)
                .block();
    }

    static String friendlyStreamError(Throwable error) {
        if (isModelTimeout(error)) {
            return "模型响应超时，已中断本次请求，请重试。";
        }
        return "模型响应中断，已保留当前生成内容，请重试。";
    }

    static String friendlyStreamError(Throwable error, String question) {
        if (isModelTimeout(error)) {
            return modelTimeoutMessage(question);
        }
        return containsHan(question)
                ? "模型响应中断，已保留当前生成内容，请重试。"
                : "The model response was interrupted. Any generated content has been preserved; please retry.";
    }

    private static String modelTimeoutMessage(String question) {
        return containsHan(question)
                ? "模型响应超时，已中断本次请求，请重试。"
                : "The model response timed out, so this request was stopped. Please retry.";
    }

    private static boolean containsHan(String value) {
        return Objects.toString(value, "").codePoints().anyMatch(codePoint ->
                Character.UnicodeScript.of(codePoint) == Character.UnicodeScript.HAN);
    }

    static boolean isModelTimeout(Throwable error) {
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (cause instanceof TimeoutException
                    || cause instanceof InterruptedIOException
                    || (cause.getMessage() != null
                    && cause.getMessage().toLowerCase(Locale.ROOT).contains("timeout"))) {
                return true;
            }
        }
        return false;
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
                                             PropertyQueryIntent propertyIntent) {
        if (propertyIntent != null && propertyIntent.propertyHandled()) {
            log.info("Property query bypasses vector retrieval: intent={}",
                    propertyIntent);
            return List.of();
        }
        String query = buildRetrievalQuery(question, history);
        boolean structuredPropertyQuery = propertyIntent != null
                && propertyIntent.structured();
        if (structuredPropertyQuery) {
            query += "\n检索公寓的地址、区域、交通、设施、附近学校和周边地点等静态资料。"
                    + "报价、租期和库存由数据库 Tool 提供。";
        }
        int topK = propertyIntent == PropertyQueryIntent.RECOMMEND
                ? Math.max(retrievalTopK, PROPERTY_CANDIDATE_RETRIEVAL_TOP_K)
                : retrievalTopK;
        List<Document> documents = retrieveForCategories(query, categoryIds, topK);
        if (!structuredPropertyQuery) {
            return documents;
        }
        List<Document> staticKnowledge = keepStaticPropertyKnowledge(documents);
        if (propertyIntent == PropertyQueryIntent.RECOMMEND) {
            List<Document> distinctResidences = distinctResidenceKnowledge(
                    staticKnowledge, PROPERTY_MAX_DISTINCT_RESIDENCES);
            log.info("Hybrid property RAG: retrieved={}, staticKnowledge={}, distinctResidences={}",
                    documents.size(), staticKnowledge.size(), distinctResidences.size());
            return distinctResidences;
        }
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
     * A broad vector search can return several chunks from the same residence.
     * Preserve retrieval order but keep one best-scoring chunk per residence so
     * location recommendations cover different candidate residences.
     */
    static List<Document> distinctResidenceKnowledge(List<Document> documents, int limit) {
        if (documents == null || documents.isEmpty() || limit <= 0) {
            return Collections.emptyList();
        }
        Map<String, Document> byResidence = new LinkedHashMap<>();
        for (Document document : documents) {
            if (document == null) {
                continue;
            }
            String residenceName = resolveResidenceName(document);
            if (residenceName == null) {
                continue;
            }
            byResidence.putIfAbsent(residenceName.toLowerCase(Locale.ROOT), document);
            if (byResidence.size() >= limit) {
                break;
            }
        }
        // Older non-residence documents do not have residenceName. Retain the
        // previous behavior only as a compatibility fallback until re-ingested.
        return byResidence.isEmpty()
                ? documents.stream().limit(limit).toList()
                : List.copyOf(byResidence.values());
    }

    /**
     * 有分类时只在指定分类中检索；无足够相关命中时直接返回空结果。
     * 不将其它分类的低相关片段作为兜底上下文，避免模型产生无依据引用。
     */
    private List<Document> retrieveForCategories(String question, List<Long> categoryIds,
                                                 int topK) {
        if (categoryIds == null || categoryIds.isEmpty()) {
            return vectorSimilaritySearch(question, null, topK);
        }

        Set<String> categoryKeys = categoryIds.stream()
                .filter(Objects::nonNull)
                .map(String::valueOf)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        if (categoryKeys.isEmpty()) {
            return vectorSimilaritySearch(question, null, topK);
        }

        try {
            List<Document> filtered = vectorSimilaritySearch(
                    question, buildCategoryIdFilter(categoryKeys), topK);
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

    private List<Document> vectorSimilaritySearch(String question, Filter.Expression filter,
                                                  int topK) {
        SearchRequest.Builder builder = SearchRequest.builder()
                .query(question)
                .topK(Math.max(topK, 1))
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

    private String buildModelSystemPrompt(String ragContext, boolean plainText) {
        // 手动模式的知识库和历史都被收敛到 user message，最终请求严格只有 system + user。
        String prompt = manualHistoryEnabled
                ? promptManager.systemPrompt()
                : promptManager.systemPrompt() + "\n\n" + ragContext;
        return plainText ? prompt + PLAIN_TEXT_OUTPUT_INSTRUCTION : prompt;
    }

    private String buildPropertyToolSystemPrompt(PropertyQueryIntent propertyIntent,
                                                 String question, boolean plainText) {
        StringBuilder prompt = new StringBuilder(propertyToolPromptManager.systemPrompt());
        if (propertyIntent == PropertyQueryIntent.CLARIFY) {
            prompt.append("""

                    本次疑似房源咨询，但用户要查询的对象、目标或动作不够明确，因此没有提供 Tool。
                    不要检索知识库，也不要猜测、推荐或列举公寓、房型、库存和价格。结合最近对话，
                    用自然友好的方式最多提出两个简短问题，只询问能够确定用户真实意图的关键信息；
                    不要一次罗列完整找房条件，也不要声称已经找到结果。
                    """);
        }
        else if (propertyIntent == PropertyQueryIntent.GUIDANCE) {
            prompt.append("""

                    本次是条件尚未确定的房源咨询，没有提供 Tool。不要列出、推荐或猜测任何公寓、
                    房型、库存或价格。自然确认用户仍在规划，并最多询问两个最关键条件，优先询问
                    预计入住时间和租期；可以补充询问学校/区域，但不要把所有条件一次性罗列出来。
                    """);
        }
        else if (propertyIntent == PropertyQueryIntent.RESTRICTED) {
            prompt.append("""

                    本次是受限房源请求，没有提供 Tool。不得披露采购价、底价、内部价格档位或代理
                    结算价，不得保证价格、锁房或确认预订。简洁说明无法执行，并引导 Londonist
                    顾问人工确认；不要检索、列举或推荐房源。
                    """);
        }
        if (propertyIntent == PropertyQueryIntent.RECOMMEND) {
            String salesPrompt = salesRecommendationPromptManager.systemPrompt();
            if (!salesPrompt.isBlank()) {
                prompt.append("\n\n").append(salesPrompt);
            }
        }
        boolean chineseQuestion = question != null
                && question.codePoints().anyMatch(codePoint ->
                Character.UnicodeScript.of(codePoint) == Character.UnicodeScript.HAN);
        prompt.append(chineseQuestion
                ? "\n\n本次用户消息的主要语言是中文，最终回答必须全部使用中文。"
                : "\n\nThe current user message is in English. Write the entire final answer in English, including table headings, statuses, and notes.");
        prompt
                .append("\n\n当前日期（Europe/London）：")
                .append(LocalDate.now(LONDON_TIME_ZONE));
        if (plainText) {
            prompt.append(PLAIN_TEXT_OUTPUT_INSTRUCTION);
        }
        return prompt.toString();
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
            String title = defaultIfBlank(
                    resolveResidenceName(document),
                    defaultIfBlank(metadataValue(metadata, "title"), "(untitled document)"));
            String contextualizedText = contextualizeKnowledgeChunk(text, metadata);

            prompt.append(promptManager.contextItem(number++, title, contextualizedText))
                    .append("\n\n");
        }

        return prompt.toString();
    }

    /**
     * Token splitting can place a Markdown heading in one chunk and its university
     * distance lines in another. Repeat stable residence identity from metadata in
     * every retrieved chunk so the model never has to guess which residence a
     * location statement belongs to.
     */
    static String contextualizeKnowledgeChunk(String text, Map<String, Object> metadata) {
        String strippedText = text == null ? "" : text.strip();
        String residenceName = metadataValue(metadata, "residenceName");
        if (residenceName == null || strippedText.startsWith("公寓名称：")) {
            return strippedText;
        }

        StringBuilder contextualized = new StringBuilder()
                .append("公寓名称：").append(residenceName).append('\n');
        appendMetadataLine(contextualized, "区域", metadataValue(metadata, "region"));
        appendMetadataLine(contextualized, "交通分区", metadataValue(metadata, "zone"));
        appendMetadataLine(contextualized, "地址", metadataValue(metadata, "address"));
        contextualized.append("\n资料片段：\n").append(strippedText);
        return contextualized.toString();
    }

    static String resolveResidenceName(Document document) {
        if (document == null) {
            return null;
        }
        String metadataName = metadataValue(
                document.getMetadata(), "residenceName");
        if (metadataName != null) {
            return metadataName;
        }
        String text = document.getText();
        if (text == null || text.isBlank()) {
            return null;
        }
        Matcher prefix = RESIDENCE_NAME_PREFIX.matcher(text);
        if (prefix.find()) {
            return prefix.group(1).strip();
        }
        Matcher heading = RESIDENCE_H2.matcher(text);
        return heading.find() ? heading.group(1).strip() : null;
    }

    private static void appendMetadataLine(StringBuilder target, String label, String value) {
        if (value != null) {
            target.append(label).append('：').append(value).append('\n');
        }
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
