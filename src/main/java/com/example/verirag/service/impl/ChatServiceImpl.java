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
import com.example.verirag.service.ChatService;
import com.example.verirag.service.RagAnswerCache;
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

import java.io.InterruptedIOException;
import java.text.Normalizer;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.YearMonth;
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
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class ChatServiceImpl implements ChatService {
    private static final int DEFAULT_RAG_TOP_K = 8;
    private static final double DEFAULT_SIMILARITY_THRESHOLD = 0.75;
    private static final double PORTFOLIO_OVERVIEW_SIMILARITY_THRESHOLD = 0.60;
    private static final int DEFAULT_HISTORY_MAX_MESSAGES = 6;
    private static final String PORTFOLIO_OVERVIEW_QUERY_MARKER = "【全量公寓统计检索】";
    private static final String SCHOOL_LOCATION_QUERY_MARKER = "【学校位置检索】";
    private static final Pattern UCL_DISTANCE_PATTERN = Pattern.compile(
            "(?i)(?:university\\s+college\\s+london\\s*\\(ucl\\)"
                    + "|ucl\\s*\\(university\\s+college\\s+london\\))[^\\r\\n\\d]*(\\d+)\\s*(?:min|dk)");
    private static final Pattern DURATION_MONTHS_PATTERN = Pattern.compile("(\\d+)\\s*个月");
    private static final Pattern DURATION_WEEKS_PATTERN = Pattern.compile(
            "(\\d+)\\s*(?:周|weeks?)", Pattern.CASE_INSENSITIVE);
    private static final Pattern WEEK_RANGE_PATTERN = Pattern.compile(
            "(\\d+)\\s*[-–—]\\s*(\\d+)\\s*weeks?", Pattern.CASE_INSENSITIVE);
    private static final Pattern REQUEST_MONTH_PATTERN = Pattern.compile(
            "(?<!\\d)(1[0-2]|0?[1-9])\\s*月份?");
    private static final Pattern DATE_RANGE_PATTERN = Pattern.compile(
            "(\\d{4})[./-](\\d{1,2})[./-](\\d{1,2})\\s*[-–—]{1,3}\\s*"
                    + "(\\d{4})[./-](\\d{1,2})[./-](\\d{1,2})");
    private static final Pattern AVAILABILITY_PATTERN = Pattern.compile(
            "(?im)\\*\\*(?:room\\s+availability|库存|房态)\\*\\*\\s*:\\s*([^\\r\\n]+)");
    private static final Pattern MARKDOWN_HEADING_PATTERN = Pattern.compile(
            "(?m)^##\\s+(.+?)\\s*$");
    private static final Pattern PROMOTION_DEADLINE_PATTERN = Pattern.compile(
            "(?:截止(?:到)?|有效期至)\\s*(?:(\\d{4})[./年-])?(\\d{1,2})[.月/-](\\d{1,2})日?");
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

    @Value("${rag.retrieval.similarity-threshold:" + DEFAULT_SIMILARITY_THRESHOLD + "}")
    private double similarityThreshold;

    @Value("${rag.retrieval.top-k:" + DEFAULT_RAG_TOP_K + "}")
    private int retrievalTopK;

    @Value("${rag.retrieval.location-top-k:50}")
    private int locationTopK;

    @Value("${rag.retrieval.location-max-candidates:12}")
    private int locationMaxCandidates;

    @Value("${rag.retrieval.room-offer-top-k:80}")
    private int roomOfferTopK;

    @Value("${rag.retrieval.room-offer-max-results:32}")
    private int roomOfferMaxResults;

    @Value("${rag.retrieval.room-offer-max-per-residence:6}")
    private int roomOfferMaxPerResidence;

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

        // 追问的答案依赖前文，不参与跨会话回答缓存，避免上下文错配。
        var cached = requestedSessionId == null
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

        //检索计时
        List<Document> cited = retrievalTimingAdvisor.advise(requestedSessionId,
                () -> retrieveForCategories(buildRetrievalQuery(req.getQuestion(), history), req.getCategoryIds()));

        long llmStart = System.nanoTime();
        String ragContext = buildRagContext(cited);
        String answer;
        try {
            answer = newChatPrompt(requestedSessionId)
                    .system(buildModelSystemPrompt(ragContext))
                    .user(buildModelUserMessage(req.getQuestion(), requestedSessionId, history, ragContext))
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
        if (requestedSessionId == null) {
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

        var cached = requestedSessionId == null
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
                () -> retrieveForCategories(buildRetrievalQuery(req.getQuestion(), history), req.getCategoryIds()));

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
        return newChatPrompt(sessionId)
                .system(buildModelSystemPrompt(ragContext))
                .user(buildModelUserMessage(req.getQuestion(), requestedSessionId, history, ragContext))
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
                    if (requestedSessionId == null) {
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

    /**
     * 有分类时只在指定分类中检索；无足够相关命中时直接返回空结果。
     * 不将其它分类的低相关片段作为兜底上下文，避免模型产生无依据引用。
     */
    private List<Document> retrieveForCategories(String question, List<Long> categoryIds) {
        if (categoryIds == null || categoryIds.isEmpty()) {
            return vectorSimilaritySearchWithLocationJoin(question, null);
        }

        Set<String> categoryKeys = categoryIds.stream()
                .filter(Objects::nonNull)
                .map(String::valueOf)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        if (categoryKeys.isEmpty()) {
            return vectorSimilaritySearch(question, null);
        }

        try {
            List<Document> filtered = vectorSimilaritySearchWithLocationJoin(
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

    /**
     * 学校附近房源需要跨两类资料：先从 HTML 位置库找到附近公寓，再用公寓名称
     * 反查 XLSX 房型价表。其它问题仍只执行一次向量检索。
     */
    private List<Document> vectorSimilaritySearchWithLocationJoin(String question,
                                                                  Filter.Expression filter) {
        boolean schoolLocationQuery = question.contains(SCHOOL_LOCATION_QUERY_MARKER);
        List<Document> initial = vectorSimilaritySearch(
                question, filter, schoolLocationQuery ? locationTopK : retrievalTopK);
        if (!schoolLocationQuery) {
            return initial;
        }

        Map<String, Document> locationsByKey = new LinkedHashMap<>();
        initial.stream()
                .filter(ChatServiceImpl::isUclMainLocationDocument)
                .sorted(Comparator.comparingInt(ChatServiceImpl::uclCommuteMinutes)
                .thenComparing(Document::getScore,
                                Comparator.nullsLast(Comparator.reverseOrder())))
                .forEach(document -> {
                    String name = residenceName(document);
                    String key = canonicalResidenceName(name);
                    if (!key.isBlank() && locationsByKey.size() < Math.max(locationMaxCandidates, 1)) {
                        locationsByKey.putIfAbsent(key, document);
                    }
                });

        List<Document> locationCandidates = new ArrayList<>(locationsByKey.values());
        List<String> residenceNames = locationCandidates.stream()
                .map(ChatServiceImpl::residenceName)
                .filter(Objects::nonNull)
                .toList();
        if (residenceNames.isEmpty()) {
            log.warn("School location join found no UCL location documents: initialDocuments={}",
                    initial.size());
            return Collections.emptyList();
        }

        Set<String> candidateKeys = new LinkedHashSet<>(locationsByKey.keySet());
        String roomOfferQuery = question + "\n"
                + "候选公寓名称：" + String.join("、", residenceNames) + "。"
                + "这些名称可能在价表中带有 Chapter、Prestige、Fresh 等品牌前缀，"
                + "或省略 Residence 后缀。"
                + "请检索这些公寓对应的房型、入住日期、租期价格和库存。";
        List<Document> retrievedOffers = vectorSimilaritySearch(
                roomOfferQuery, filter, Math.max(roomOfferTopK, retrievalTopK));
        List<Document> canonicalOffers = retrievedOffers.stream()
                .filter(document -> matchesCandidateResidence(document, candidateKeys))
                .toList();
        List<Document> matchingOffers = canonicalOffers.stream()
                .filter(document -> matchesRoomRequest(document, question))
                .map(document -> annotateExpiredPromotion(
                        document, LocalDate.now(LONDON_TIME_ZONE)))
                .sorted(Comparator.comparing(ChatServiceImpl::isPromotionExpired))
                .toList();
        List<Document> roomOffers = limitRoomOffersByResidence(matchingOffers,
                roomOfferMaxPerResidence, roomOfferMaxResults);
        log.info("School location join: initial={}, locations={}, roomRetrieved={}, "
                        + "nameMatched={}, requestMatched={}, selected={}",
                initial.size(), locationCandidates.size(), retrievedOffers.size(),
                canonicalOffers.size(), matchingOffers.size(), roomOffers.size());

        Map<String, Document> merged = new LinkedHashMap<>();
        Set<String> matchedKeys = roomOffers.stream()
                .map(ChatServiceImpl::propertyName)
                .filter(Objects::nonNull)
                .map(ChatServiceImpl::canonicalResidenceName)
                .collect(Collectors.toSet());
        for (Map.Entry<String, Document> entry : locationsByKey.entrySet()) {
            if (roomOffers.isEmpty() || matchedKeys.contains(entry.getKey())) {
                merged.put(documentKey(entry.getValue()), entry.getValue());
            }
        }
        for (Document document : roomOffers) {
            merged.putIfAbsent(documentKey(document), document);
        }
        return new ArrayList<>(merged.values());
    }

    static boolean isUclMainLocationDocument(Document document) {
        if (document == null) {
            return false;
        }
        String text = Optional.ofNullable(document.getText()).orElse("");
        String normalized = text.toLowerCase(Locale.ROOT);
        boolean locationDocument = (document.getMetadata() != null
                && metadataValue(document.getMetadata(), "residenceId") != null)
                || (text.contains("### 附近大学")
                && (text.contains("**地址**") || text.contains("**区域**")));
        return locationDocument
                && (normalized.contains("university college london (ucl)")
                || normalized.contains("ucl (university college london)"));
    }

    static int uclCommuteMinutes(Document document) {
        String text = document == null || document.getText() == null ? "" : document.getText();
        Matcher matcher = UCL_DISTANCE_PATTERN.matcher(text);
        return matcher.find() ? Integer.parseInt(matcher.group(1)) : Integer.MAX_VALUE;
    }

    static boolean matchesRoomRequest(Document document, String question) {
        String text = document == null || document.getText() == null ? "" : document.getText();
        Matcher availability = AVAILABILITY_PATTERN.matcher(text);
        if (!availability.find()) {
            return false;
        }
        String status = availability.group(1).strip().toLowerCase(Locale.ROOT);
        if (status.isBlank()) {
            return false;
        }

        int requestedWeeks = requestedDurationWeeks(question);
        if (requestedWeeks > 0 && !supportsWeeks(text, requestedWeeks)) {
            return false;
        }
        int requestedMonth = requestedStartMonth(question);
        return requestedMonth <= 0 || supportsStartMonth(text, requestedMonth);
    }

    static Document annotateExpiredPromotion(Document document, LocalDate today) {
        if (document == null || document.getText() == null || today == null) {
            return document;
        }
        Matcher deadline = PROMOTION_DEADLINE_PATTERN.matcher(document.getText());
        if (!deadline.find()) {
            return document;
        }
        int year = deadline.group(1) == null
                ? inferOfferYear(document.getText(), today.getYear())
                : Integer.parseInt(deadline.group(1));
        try {
            LocalDate expiry = LocalDate.of(year,
                    Integer.parseInt(deadline.group(2)), Integer.parseInt(deadline.group(3)));
            if (!expiry.isBefore(today)) {
                return document;
            }
            Map<String, Object> metadata = new LinkedHashMap<>(document.getMetadata());
            metadata.put("promotionExpired", true);
            String warning = "\n- **系统校验**: 限时优惠已于 " + expiry
                    + " 过期；房型库存仍可作为候选，但表中价格不能视为当前有效报价，需重新确认。";
            return document.mutate()
                    .text(document.getText().stripTrailing() + warning)
                    .metadata(metadata)
                    .build();
        }
        catch (DateTimeException | NumberFormatException ex) {
            return document;
        }
    }

    private static int inferOfferYear(String text, int fallbackYear) {
        Matcher range = DATE_RANGE_PATTERN.matcher(text);
        return range.find() ? Integer.parseInt(range.group(1)) : fallbackYear;
    }

    private static boolean isPromotionExpired(Document document) {
        return document != null
                && Boolean.TRUE.equals(document.getMetadata().get("promotionExpired"));
    }

    private static int requestedDurationWeeks(String question) {
        String value = question == null ? "" : question;
        Matcher months = DURATION_MONTHS_PATTERN.matcher(value);
        if (months.find()) {
            return (int) Math.round(Integer.parseInt(months.group(1)) * 52.0 / 12.0);
        }
        Matcher weeks = DURATION_WEEKS_PATTERN.matcher(value);
        return weeks.find() ? Integer.parseInt(weeks.group(1)) : -1;
    }

    private static boolean supportsWeeks(String text, int requestedWeeks) {
        Matcher matcher = WEEK_RANGE_PATTERN.matcher(text);
        boolean foundRange = false;
        while (matcher.find()) {
            foundRange = true;
            int minimum = Integer.parseInt(matcher.group(1));
            int maximum = Integer.parseInt(matcher.group(2));
            if (requestedWeeks >= minimum && requestedWeeks <= maximum) {
                return true;
            }
        }
        return !foundRange;
    }

    private static int requestedStartMonth(String question) {
        Matcher matcher = REQUEST_MONTH_PATTERN.matcher(question == null ? "" : question);
        return matcher.find() ? Integer.parseInt(matcher.group(1)) : -1;
    }

    private static boolean supportsStartMonth(String text, int requestedMonth) {
        Matcher matcher = DATE_RANGE_PATTERN.matcher(text);
        if (!matcher.find()) {
            return true;
        }
        try {
            LocalDate start = LocalDate.of(Integer.parseInt(matcher.group(1)),
                    Integer.parseInt(matcher.group(2)), Integer.parseInt(matcher.group(3)));
            LocalDate end = LocalDate.of(Integer.parseInt(matcher.group(4)),
                    Integer.parseInt(matcher.group(5)), Integer.parseInt(matcher.group(6)));
            YearMonth cursor = YearMonth.from(start);
            YearMonth last = YearMonth.from(end);
            for (int count = 0; !cursor.isAfter(last) && count < 36; count++, cursor = cursor.plusMonths(1)) {
                if (cursor.getMonthValue() == requestedMonth) {
                    return true;
                }
            }
            return false;
        }
        catch (DateTimeException | NumberFormatException ex) {
            return true;
        }
    }

    private static List<Document> limitRoomOffersByResidence(List<Document> offers,
                                                             int maxPerResidence,
                                                             int maxTotal) {
        int perResidenceLimit = Math.max(maxPerResidence, 1);
        int totalLimit = Math.max(maxTotal, 1);
        Map<String, Integer> counts = new LinkedHashMap<>();
        List<Document> selected = new ArrayList<>();
        for (Document offer : offers) {
            String propertyName = propertyName(offer);
            String key = canonicalResidenceName(propertyName);
            int count = counts.getOrDefault(key, 0);
            if (key.isBlank() || count >= perResidenceLimit) {
                continue;
            }
            selected.add(offer);
            counts.put(key, count + 1);
            if (selected.size() >= totalLimit) {
                break;
            }
        }
        return selected;
    }

    private static String residenceName(Document document) {
        String metadataName = document == null || document.getMetadata() == null
                ? null : metadataValue(document.getMetadata(), "residenceName");
        return metadataName == null || metadataName.isBlank()
                ? markdownHeading(document) : metadataName;
    }

    private static String propertyName(Document document) {
        String metadataName = document == null || document.getMetadata() == null
                ? null : metadataValue(document.getMetadata(), "propertyName");
        return metadataName == null || metadataName.isBlank()
                ? markdownHeading(document) : metadataName;
    }

    private static String markdownHeading(Document document) {
        String text = document == null || document.getText() == null ? "" : document.getText();
        Matcher matcher = MARKDOWN_HEADING_PATTERN.matcher(text);
        return matcher.find() ? matcher.group(1).strip() : "";
    }

    static String canonicalResidenceName(String name) {
        if (name == null || name.isBlank()) {
            return "";
        }
        String normalized = Normalizer.normalize(name, Normalizer.Form.NFKD)
                .replaceAll("\\p{M}+", "")
                .toLowerCase(Locale.ROOT)
                .replace('’', '\'')
                .replace('（', '(')
                .trim();
        normalized = normalized
                .replaceFirst("\\s*\\(.*$", "")
                .replaceFirst("\\s+\\d{2}\\s*[-/]\\s*\\d{2}\\s+academic\\s+year.*$", "")
                .replaceFirst("^(?:chapter|prestige|fresh|downing|mezzino|fusion|unite\\s+students?)\\s+", "")
                .replaceFirst("\\s+(?:residence|student\\s+accommodation)$", "");
        return normalized.replaceAll("[^a-z0-9]+", "");
    }

    private static boolean matchesCandidateResidence(Document document, Set<String> candidateKeys) {
        if (candidateKeys.isEmpty() || document == null) {
            return false;
        }
        String propertyName = propertyName(document);
        return propertyName != null
                && candidateKeys.contains(canonicalResidenceName(propertyName));
    }

    private static String documentKey(Document document) {
        String text = document.getText();
        return text == null ? String.valueOf(document.getMetadata()) : text;
    }

    private List<Document> vectorSimilaritySearch(String question, Filter.Expression filter) {
        return vectorSimilaritySearch(question, filter, retrievalTopK);
    }

    private List<Document> vectorSimilaritySearch(String question, Filter.Expression filter, int topK) {
        boolean specializedQuery = question.contains(PORTFOLIO_OVERVIEW_QUERY_MARKER)
                || question.contains(SCHOOL_LOCATION_QUERY_MARKER);
        double effectiveThreshold = specializedQuery
                ? Math.min(similarityThreshold, PORTFOLIO_OVERVIEW_SIMILARITY_THRESHOLD)
                : similarityThreshold;
        SearchRequest.Builder builder = SearchRequest.builder()
                .query(question)
                .topK(topK)
                .similarityThreshold(effectiveThreshold);
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
                        && document.getScore() >= effectiveThreshold)
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
        if (isPortfolioOverviewQuestion(question)) {
            return query + "\n" + PORTFOLIO_OVERVIEW_QUERY_MARKER
                    + " Londonist 伦敦公寓位置总览，伦敦公寓总数，完整公寓名单，"
                    + "东伦敦、西伦敦、北伦敦、南伦敦区域统计。";
        }
        if (isUclMainCampusQuestion(question)) {
            return query + "\n" + SCHOOL_LOCATION_QUERY_MARKER
                    + " University College London (UCL) 主校区，Bloomsbury 校区附近公寓，"
                    + "附近大学距离。排除 UCL East、Stratford 和 Olympic Park 校区。";
        }
        return query;
    }

    private static boolean isPortfolioOverviewQuestion(String question) {
        if (question == null || question.isBlank()
                || (!question.contains("公寓") && !question.contains("房源"))) {
            return false;
        }
        String normalized = question.replaceAll("\\s+", "");
        return normalized.matches(".*(?:多少|几个|总数|数量).*(?:公寓|房源).*")
                || normalized.matches(".*(?:公寓|房源).*(?:多少|几个|总数|数量).*")
                || normalized.matches(".*(?:全部|所有|完整).*(?:公寓|房源).*(?:名单|列表).*")
                || normalized.matches(".*(?:公寓|房源).*(?:完整名单|完整列表).*");
    }

    private static boolean isUclMainCampusQuestion(String question) {
        if (question == null || question.isBlank()) {
            return false;
        }
        String normalized = question.toLowerCase(Locale.ROOT).replaceAll("\\s+", "");
        boolean mentionsUcl = normalized.contains("ucl")
                || normalized.contains("universitycollegelondon")
                || normalized.contains("伦敦大学学院");
        return mentionsUcl && !normalized.contains("ucleast");
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
