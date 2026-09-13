package com.example.verirag.integration.wecom;

import com.example.verirag.config.WeComKfProperties;
import com.example.verirag.dto.ChatAskRequest;
import com.example.verirag.dto.ChatAskResult;
import com.example.verirag.mapper.WeComConversationMapper;
import com.example.verirag.mapper.WeComKfPendingMessageMapper;
import com.example.verirag.mapper.WeComKfStateMapper;
import com.example.verirag.observability.WeComKfMetrics;
import com.example.verirag.service.ChatService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.task.TaskExecutor;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ScheduledFuture;

/**
 * 消费微信客服回调通知，通过 sync_msg 拉取消息并调用 Veri-RAG 回复。
 */
@Service
@Slf4j
@ConditionalOnProperty(prefix = "wecom.kf", name = "enabled", havingValue = "true")
public class WeComKfMessageService {

    private static final int MAX_TEXT_BYTES = 2048;
    private final WeComKfProperties properties;
    private final WeComKfApiClient apiClient;
    private final WeComKfStateMapper stateMapper;
    private final WeComKfPendingMessageMapper pendingMessageMapper;
    private final WeComConversationMapper conversationMapper;
    private final ChatService chatService;
    private final TaskScheduler progressScheduler;
    private final TaskExecutor messageExecutor;
    private final WeComKfMetrics metrics;
    private final ObjectMapper objectMapper;
    private final WeComCustomerService customers;
    private final ConcurrentHashMap<String, Object> accountLocks = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, CompletableFuture<Void>> customerQueues =
            new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, PendingCustomerMessages> pendingMessages =
            new ConcurrentHashMap<>();
    private final java.util.Set<String> inFlightMessageIds = ConcurrentHashMap.newKeySet();

    public WeComKfMessageService(
            WeComKfProperties properties,
            WeComKfApiClient apiClient,
            WeComKfStateMapper stateMapper,
            WeComKfPendingMessageMapper pendingMessageMapper,
            WeComConversationMapper conversationMapper,
            ChatService chatService,
            @Qualifier("wecomKfProgressScheduler") TaskScheduler progressScheduler,
            @Qualifier("wecomKfExecutor") TaskExecutor messageExecutor,
            WeComKfMetrics metrics,
            ObjectMapper objectMapper, WeComCustomerService customers) {
        this.properties = properties;
        this.apiClient = apiClient;
        this.stateMapper = stateMapper;
        this.pendingMessageMapper = pendingMessageMapper;
        this.conversationMapper = conversationMapper;
        this.chatService = chatService;
        this.progressScheduler = progressScheduler;
        this.messageExecutor = messageExecutor;
        this.metrics = metrics;
        this.objectMapper = objectMapper;
        this.customers = customers;
    }

    @PostConstruct
    void schedulePendingRecovery() {
        progressScheduler.scheduleAtFixedRate(this::recoverPendingMessages, Duration.ofSeconds(15));
    }

    /** HTTP 回调必须快速返回，具体同步与模型调用放入独立线程池。 */
    @Async("wecomKfExecutor")
    public void handleNotification(String callbackToken, String openKfId) {
        long syncStarted = System.nanoTime();
        java.util.List<JsonNode> messages;
        Object lock = accountLocks.computeIfAbsent(openKfId, ignored -> new Object());
        synchronized (lock) {
            try {
                // 账号锁只覆盖 sync_msg/cursor，不能把大模型调用放在锁里。
                messages = syncAll(callbackToken, openKfId);
            } catch (Exception ex) {
                log.error("event=wecom.kf.sync_failed openKfId={} error={}",
                        openKfId, rootMessage(ex));
                metrics.recordSync(Duration.ofNanos(System.nanoTime() - syncStarted), "error");
                return;
            }
        }
        metrics.recordSync(Duration.ofNanos(System.nanoTime() - syncStarted), "success");
        metrics.increment("synced");
        messages.forEach(this::enqueueMessage);
    }

    private java.util.List<JsonNode> syncAll(String callbackToken, String openKfId) {
        String cursor = stateMapper.selectCursor(openKfId);
        java.util.List<JsonNode> messages = new java.util.ArrayList<>();
        boolean hasMore;
        do {
            JsonNode page = apiClient.syncMessages(openKfId, callbackToken, cursor);
            page.path("msg_list").forEach(message -> {
                customers.observe(message);
                if (claimPendingMessage(message)) {
                    messages.add(message);
                } else {
                    metrics.increment("duplicate");
                }
            });
            String nextCursor = page.path("next_cursor").asText("");
            hasMore = page.path("has_more").asInt(0) == 1;
            if (StringUtils.hasText(nextCursor)) {
                stateMapper.upsertCursor(openKfId, nextCursor);
                cursor = nextCursor;
            } else if (hasMore) {
                throw new IllegalStateException("WeCom sync_msg returned has_more=1 without next_cursor");
            }
        } while (hasMore);
        return messages;
    }

    /** 游标推进前先持久化消息；进程在任意阶段退出都可以从该表恢复。 */
    private boolean claimPendingMessage(JsonNode message) {
        String messageId = message.path("msgid").asText("");
        if (!StringUtils.hasText(messageId) || stateMapper.isProcessed(messageId)) {
            return false;
        }
        return pendingMessageMapper.insertPending(messageId,
                blankToNull(message.path("open_kfid").asText("")),
                blankToNull(message.path("external_userid").asText("")),
                message.toString()) > 0;
    }

    void recoverPendingMessages() {
        try {
            for (var pending : pendingMessageMapper.listPendingMessages(
                    100, java.util.List.copyOf(inFlightMessageIds))) {
                try {
                    JsonNode message = objectMapper.readTree(pending.payloadJson());
                    if (message == null || !pending.messageId().equals(message.path("msgid").asText(""))) {
                        throw new IllegalArgumentException("Pending message ID does not match payload");
                    }
                    enqueueMessage(message);
                } catch (Exception ex) {
                    recordFailure(pending.messageId(), ex);
                }
            }
        } catch (Exception ex) {
            log.warn("event=wecom.kf.pending_recovery_failed error={}", rootMessage(ex));
        }
    }

    private void recordFailure(String messageId, Exception error) {
        if (stateMapper.isProcessed(messageId)) {
            return;
        }
        pendingMessageMapper.recordFailure(messageId);
        int failures = pendingMessageMapper.failureCount(messageId);
        log.error("event=wecom.kf.message_retry_recorded msgId={} failures={} isolated={} error={}",
                messageId, failures, failures >= 6, rootMessage(error));
        if (failures >= 6) {
            metrics.increment("retry_exhausted");
        }
    }

    /** 同一客户保持顺序，不同客户可以并发，避免一个慢模型调用阻塞整个客服账号。 */
    private void enqueueMessage(JsonNode message) {
        String messageId = message.path("msgid").asText("");
        if (!StringUtils.hasText(messageId) || stateMapper.isProcessed(messageId)
                || !inFlightMessageIds.add(messageId)) {
            metrics.increment("duplicate");
            return;
        }
        if (isMergeableCustomerText(message)) {
            mergeCustomerText(customerKey(message), message);
            return;
        }
        enqueueClaimedMessages(customerKey(message), java.util.List.of(message));
    }

    private boolean isMergeableCustomerText(JsonNode message) {
        return message.path("origin").asInt(0) == 3
                && "text".equals(message.path("msgtype").asText(""))
                && StringUtils.hasText(message.path("open_kfid").asText(""))
                && StringUtils.hasText(message.path("external_userid").asText(""));
    }

    private void mergeCustomerText(String key, JsonNode message) {
        pendingMessages.compute(key, (ignored, pending) -> {
            PendingCustomerMessages target = pending == null
                    ? new PendingCustomerMessages() : pending;
            synchronized (target) {
                target.messages.add(message);
                if (target.future != null) {
                    target.future.cancel(false);
                }
                Duration delay = properties.getMessageMergeWindow();
                if (delay == null || delay.isNegative()) {
                    delay = Duration.ZERO;
                }
                if (delay.isZero()) {
                    delay = Duration.ofMillis(1);
                }
                PendingCustomerMessages scheduled = target;
                target.future = progressScheduler.schedule(
                        () -> flushMergedMessages(key, scheduled), Instant.now().plus(delay));
            }
            return target;
        });
    }

    private void flushMergedMessages(String key, PendingCustomerMessages pending) {
        if (!pendingMessages.remove(key, pending)) {
            return;
        }
        java.util.List<JsonNode> messages;
        synchronized (pending) {
            messages = java.util.List.copyOf(pending.messages);
        }
        if (messages.size() > 1) {
            metrics.increment("merged");
        }
        enqueueClaimedMessages(key, messages);
    }

    private void enqueueClaimedMessages(String key, java.util.List<JsonNode> messages) {
        long queuedAt = System.nanoTime();
        CompletableFuture<Void> queued;
        try {
            queued = customerQueues.compute(key, (ignored, tail) -> {
                CompletableFuture<Void> previous = tail == null
                        ? CompletableFuture.completedFuture(null)
                        : tail.handle((unused, error) -> null);
                return previous.thenRunAsync(() -> {
                    metrics.recordQueue(Duration.ofNanos(System.nanoTime() - queuedAt));
                    long replyStarted = System.nanoTime();
                    try {
                        processMessages(messages);
                        metrics.recordReply(Duration.ofNanos(System.nanoTime() - replyStarted), "success");
                        metrics.increment("answered");
                    } catch (RuntimeException ex) {
                        metrics.recordReply(Duration.ofNanos(System.nanoTime() - replyStarted), "error");
                        metrics.increment("failed");
                        log.error("event=wecom.kf.message_processing_failed messageIds={} "
                                        + "openKfId={} externalUserId={} error={}",
                                messages.stream().map(item -> item.path("msgid").asText(""))
                                        .toList(),
                                messages.getFirst().path("open_kfid").asText(""),
                                messages.getFirst().path("external_userid").asText(""),
                                rootMessage(ex));
                        throw ex;
                    }
                }, messageExecutor);
            });
        } catch (RuntimeException ex) {
            finishQueuedMessages(messages, ex);
            return;
        }
        // 在 compute 之外清理，兼容任务立即完成；拒绝执行也必须释放 in-flight 标记。
        queued.whenComplete((unused, error) -> {
            try {
                finishQueuedMessages(messages, error);
            } finally {
                customerQueues.remove(key, queued);
            }
        });
    }

    private void finishQueuedMessages(java.util.List<JsonNode> messages, Throwable error) {
        for (JsonNode message : messages) {
            String id = message.path("msgid").asText("");
            try {
                if (error != null) {
                    recordFailure(id, new IllegalStateException(error));
                }
            } catch (RuntimeException ex) {
                log.error("event=wecom.kf.retry_state_failed msgId={} error={}", id, rootMessage(ex));
            } finally {
                inFlightMessageIds.remove(id);
            }
        }
    }

    private void processMessages(java.util.List<JsonNode> messages) {
        if (messages.size() == 1) {
            processMessage(messages.getFirst());
            return;
        }
        JsonNode first = messages.getFirst();
        String openKfId = first.path("open_kfid").asText("");
        String externalUserId = first.path("external_userid").asText("");
        String question = messages.stream()
                .map(item -> item.path("text").path("content").asText("").trim())
                .filter(StringUtils::hasText)
                .collect(java.util.stream.Collectors.joining("\n"));
        if (!StringUtils.hasText(question)) {
            messages.forEach(item -> markProcessed(item.path("msgid").asText(""),
                    openKfId, externalUserId, "text"));
            return;
        }
        int serviceState = apiClient.getServiceState(openKfId, externalUserId);
        if (serviceState == 2) {
            resumeWaitingSession(openKfId, externalUserId,
                    first.path("msgid").asText(""));
            messages.forEach(item -> markProcessed(item.path("msgid").asText(""),
                    openKfId, externalUserId, "text"));
            return;
        }
        if (serviceState == 3 || serviceState == 4) {
            messages.forEach(item -> markProcessed(item.path("msgid").asText(""),
                    openKfId, externalUserId, "text"));
            return;
        }
        if (serviceState == 0) {
            apiClient.transitionToAssistant(openKfId, externalUserId);
        } else if (serviceState != 1) {
            throw new IllegalStateException("Unknown WeCom KF service_state: " + serviceState);
        }
        answerQuestion(first.path("msgid").asText(""), openKfId, externalUserId, question);
        messages.forEach(item -> markProcessed(item.path("msgid").asText(""),
                openKfId, externalUserId, "text"));
    }

    void processMessage(JsonNode message) {
        String messageId = message.path("msgid").asText("");
        if (!StringUtils.hasText(messageId) || stateMapper.isProcessed(messageId)) {
            return;
        }
        String openKfId = message.path("open_kfid").asText("");
        String externalUserId = message.path("external_userid").asText("");
        String messageType = message.path("msgtype").asText("");
        int origin = message.path("origin").asInt(0);

        if ("event".equals(messageType)
                && "enter_session".equals(message.path("event").path("event_type").asText(""))) {
            JsonNode event = message.path("event");
            openKfId = firstText(openKfId, event.path("open_kfid").asText(""));
            externalUserId = firstText(
                    externalUserId, event.path("external_userid").asText(""));
            sendWelcomeMessage(messageId, openKfId, externalUserId, event);
            markProcessed(messageId, openKfId, externalUserId, messageType);
            return;
        }

        if (origin == 3) {
            String customerContent = "text".equals(messageType)
                    ? message.path("text").path("content").asText("")
                    : "[非文字消息:" + messageType + "]";
            log.info("event=wecom.kf.customer_message_received msgId={} openKfId={} "
                            + "externalUserId={} type={} chars={}",
                    messageId, openKfId, externalUserId, messageType,
                    customerContent.codePointCount(0, customerContent.length()));
        }

        // 只回答微信客户发来的消息；系统事件和接待人员消息仅记为已消费。
        if (origin != 3 || !StringUtils.hasText(openKfId)
                || !StringUtils.hasText(externalUserId)) {
            markProcessed(messageId, openKfId, externalUserId, messageType);
            return;
        }

        int serviceState = apiClient.getServiceState(openKfId, externalUserId);
        // 旧版本可能把会话留在待接入池；有在线接待人员则立即分配，否则结束后恢复机器人入口。
        if (serviceState == 2) {
            resumeWaitingSession(openKfId, externalUserId, messageId);
            markProcessed(messageId, openKfId, externalUserId, messageType);
            return;
        }
        // 3=人工接待、4=已结束；这些状态下机器人不能抢答。
        if (serviceState == 3 || serviceState == 4) {
            log.info("event=wecom.kf.message_skipped reason=service_state state={} "
                            + "openKfId={} externalUserId={}",
                    serviceState, openKfId, externalUserId);
            markProcessed(messageId, openKfId, externalUserId, messageType);
            return;
        }
        if (!"text".equals(messageType)) {
            ensureAssistantState(openKfId, externalUserId, serviceState);
            sendSystemText(openKfId, externalUserId, messageId, "final",
                    properties.getUnsupportedMessage());
            markProcessed(messageId, openKfId, externalUserId, messageType);
            return;
        }

        String question = message.path("text").path("content").asText("").trim();
        if (!StringUtils.hasText(question)) {
            markProcessed(messageId, openKfId, externalUserId, messageType);
            return;
        }
        ensureAssistantState(openKfId, externalUserId, serviceState);
        answerQuestion(messageId, openKfId, externalUserId, question);
        markProcessed(messageId, openKfId, externalUserId, messageType);
    }

    private void ensureAssistantState(
            String openKfId, String externalUserId, int serviceState) {
        if (serviceState == 0) {
            apiClient.transitionToAssistant(openKfId, externalUserId);
        } else if (serviceState != 1) {
            throw new IllegalStateException("Unknown WeCom KF service_state: " + serviceState);
        }
    }

    private void handoffOrContinueWithAssistant(
            String openKfId, String externalUserId, String messageId, int serviceState) {
        HandoffAttempt attempt = tryActiveServicers(
                openKfId, externalUserId, messageId);
        if (attempt.assigned()) {
            return;
        }
        // 分配人工失败时继续保持机器人接待，不能让客户进入无人处理状态。
        ensureAssistantState(openKfId, externalUserId, serviceState);
        sendSystemText(openKfId, externalUserId, messageId, "handoff-unavailable",
                properties.getHandoffMessage());
        metrics.increment("handoff_unavailable");
    }

    private void resumeWaitingSession(
            String openKfId, String externalUserId, String inboundMessageId) {
        HandoffAttempt attempt = tryActiveServicers(
                openKfId, externalUserId, inboundMessageId);
        if (attempt.assigned()) {
            return;
        }

        // 状态可能在“查询”和“分配”之间被人工端改变，先重新读取再决定后续动作。
        int latestState = apiClient.getServiceState(openKfId, externalUserId);
        if (latestState == 3 || latestState == 4) {
            log.info("event=wecom.kf.waiting_session_changed openKfId={} "
                            + "externalUserId={} latestState={}",
                    openKfId, externalUserId, latestState);
            return;
        }
        if (latestState == 0 || latestState == 1) {
            ensureAssistantState(openKfId, externalUserId, latestState);
            sendSystemText(openKfId, externalUserId, inboundMessageId,
                    "stuck-session-recovery", properties.getStuckSessionRecoveryMessage());
            metrics.increment("stuck_session_recovered");
            return;
        }

        // 企业微信不允许 2→1 或 2→4，只能等待可用接待人员并转换为 3。
        throw attempt.lastError() != null ? attempt.lastError()
                : new IllegalStateException("WeCom session is waiting but has no active servicer");
    }

    private HandoffAttempt tryActiveServicers(
            String openKfId, String externalUserId, String inboundMessageId) {
        java.util.List<WeComKfApiClient.KfServicer> active = activeServicers(
                openKfId, externalUserId);
        RuntimeException lastError = null;
        for (WeComKfApiClient.KfServicer servicer : active) {
            try {
                transferToHuman(
                        openKfId, externalUserId, inboundMessageId, servicer.userId());
                return new HandoffAttempt(true, null);
            } catch (RuntimeException ex) {
                lastError = ex;
                metrics.increment("handoff_failed");
                log.warn("event=wecom.kf.handoff_failed openKfId={} externalUserId={} "
                                + "servicerUserId={} error={}",
                        openKfId, externalUserId, servicer.userId(), rootMessage(ex));
            }
        }
        return new HandoffAttempt(false, lastError);
    }

    private java.util.List<WeComKfApiClient.KfServicer> activeServicers(
            String openKfId, String externalUserId) {
        java.util.List<WeComKfApiClient.KfServicer> active = apiClient.listServicers(openKfId)
                .stream()
                .filter(WeComKfApiClient.KfServicer::active)
                .sorted(java.util.Comparator.comparing(WeComKfApiClient.KfServicer::userId))
                .toList();
        if (active.isEmpty()) {
            return active;
        }
        int index = Math.floorMod(externalUserId.hashCode(), active.size());
        java.util.List<WeComKfApiClient.KfServicer> ordered = new java.util.ArrayList<>(active.size());
        for (int offset = 0; offset < active.size(); offset++) {
            ordered.add(active.get((index + offset) % active.size()));
        }
        return java.util.List.copyOf(ordered);
    }

    private void transferToHuman(
            String openKfId, String externalUserId, String inboundMessageId,
            String servicerUserId) {
        String msgCode = apiClient.transitionToHuman(
                openKfId, externalUserId, servicerUserId);
        if (!StringUtils.hasText(msgCode)) {
            log.warn("event=wecom.kf.handoff_notice_skipped openKfId={} externalUserId={} "
                            + "servicerUserId={} reason=empty_msg_code",
                    openKfId, externalUserId, servicerUserId);
        } else {
            try {
                apiClient.sendEventText(msgCode,
                        replyMessageId(inboundMessageId, "handoff-success"),
                        truncateUtf8(properties.getHandoffSuccessMessage(), MAX_TEXT_BYTES));
                archiveSent(openKfId, externalUserId, replyMessageId(inboundMessageId, "handoff-success"),
                        "SYSTEM", truncateUtf8(properties.getHandoffSuccessMessage(), MAX_TEXT_BYTES));
            } catch (RuntimeException ex) {
                // 人工分配已经成功；提示语失败不能回滚分配，也不能触发重复转接。
                log.warn("event=wecom.kf.handoff_notice_failed openKfId={} "
                                + "externalUserId={} servicerUserId={} error={}",
                        openKfId, externalUserId, servicerUserId, rootMessage(ex));
            }
        }
        metrics.increment("handoff_success");
        log.info("event=wecom.kf.handoff_success openKfId={} externalUserId={} servicerUserId={}",
                openKfId, externalUserId, servicerUserId);
    }

    private void sendWelcomeMessage(
            String messageId, String openKfId, String externalUserId, JsonNode event) {
        String welcomeCode = event.path("welcome_code").asText("");
        if (!StringUtils.hasText(welcomeCode)
                || !StringUtils.hasText(properties.getWelcomeMessage())) {
            return;
        }
        apiClient.sendEventText(welcomeCode, replyMessageId(messageId, "welcome"),
                truncateUtf8(properties.getWelcomeMessage(), MAX_TEXT_BYTES));
        archiveSent(openKfId, externalUserId, replyMessageId(messageId, "welcome"),
                "SYSTEM", truncateUtf8(properties.getWelcomeMessage(), MAX_TEXT_BYTES));
        metrics.increment("welcome_sent");
        log.info("event=wecom.kf.welcome_sent openKfId={} externalUserId={}",
                openKfId, externalUserId);
    }

    private void answerQuestion(
            String messageId, String openKfId, String externalUserId, String question) {
        String channelId = "kf:" + openKfId;
        ChatAskRequest request = new ChatAskRequest();
        request.setQuestion(question);
        request.setPlainText(true);
        request.setAllowHumanHandoff(true);
        request.setSessionId(conversationMapper.selectSessionId(
                channelId, externalUserId, properties.getUserId()));
        ProgressNotice progressNotice = new ProgressNotice(
                openKfId, externalUserId, messageId);
        progressNotice.start();
        ChatAskResult result;
        try {
            result = chatService.ask(properties.getUserId(), request);
            if (!result.isHumanHandoff() && result.getSessionId() != null) {
                conversationMapper.upsertSessionId(
                        channelId, externalUserId, properties.getUserId(), result.getSessionId());
            }
        } catch (Exception ex) {
            log.warn("event=wecom.kf.answer_failed openKfId={} externalUserId={} error={}",
                    openKfId, externalUserId, rootMessage(ex));
            progressNotice.finish();
            sendSystemText(openKfId, externalUserId, messageId, "final",
                    properties.getErrorMessage());
            return;
        }
        // 发送层会使用同一幂等 msgid 做短暂重试；最终失败才交给队列错误处理。
        progressNotice.finish();
        if (result.isHumanHandoff()) {
            handoffOrContinueWithAssistant(openKfId, externalUserId, messageId,
                    apiClient.getServiceState(openKfId, externalUserId));
            return;
        }
        sendSystemTexts(openKfId, externalUserId, messageId, "final",
                WeComPlainTextFormatter.format(result.getAnswer()));
        log.info("event=wecom.kf.answer_sent openKfId={} externalUserId={} sessionId={}",
                openKfId, externalUserId, result.getSessionId());
    }

    private void sendSystemText(
            String openKfId, String externalUserId, String inboundMessageId,
            String messageKind, String content) {
        String outgoingMessageId = replyMessageId(inboundMessageId, messageKind);
        String outgoingContent = truncateUtf8(content, MAX_TEXT_BYTES);
        RuntimeException lastError = null;
        for (int attempt = 1; attempt <= 3; attempt++) {
            try {
                apiClient.sendText(openKfId, externalUserId, outgoingMessageId, outgoingContent);
                if (attempt > 1) {
                    metrics.increment("send_retried");
                }
                lastError = null;
                break;
            } catch (RuntimeException ex) {
                lastError = ex;
                if (attempt < 3) {
                    log.warn("event=wecom.kf.send_retry msgId={} attempt={} error={}",
                            outgoingMessageId, attempt, rootMessage(ex));
                }
            }
        }
        if (lastError != null) {
            throw lastError;
        }
        archiveSent(openKfId, externalUserId, outgoingMessageId,
                messageKind.startsWith("final-") || "final".equals(messageKind) ? "BOT" : "SYSTEM", outgoingContent);
        log.info("event=wecom.kf.system_message_sent msgId={} replyTo={} openKfId={} "
                        + "externalUserId={} chars={}",
                outgoingMessageId, inboundMessageId, openKfId, externalUserId,
                outgoingContent.codePointCount(0, outgoingContent.length()));
    }

    /** 展示用存档不改变已经完成的发送结果。 */
    private void archiveSent(String openKfId, String externalUserId, String messageId, String speaker, String content) {
        try {
            customers.record(openKfId, externalUserId, messageId, speaker, "text", content, java.time.LocalDateTime.now());
        } catch (RuntimeException ex) {
            // 已发送的消息不能因展示存档失败再次发送。
            log.error("event=wecom.kf.outbound_archive_failed msgId={}", messageId);
        }
    }

    /** 企业微信单条文本最多 2048 字节；按 UTF-8 边界拆分，避免静默丢失后半段内容。 */
    private void sendSystemTexts(
            String openKfId, String externalUserId, String inboundMessageId,
            String messageKind, String content) {
        // 预留分段序号的字节，确保加上“（1/2）”后仍不超过企业微信上限。
        java.util.List<String> parts = splitUtf8(content, MAX_TEXT_BYTES - 32);
        if (parts.size() > 1) {
            metrics.increment("reply_split");
        }
        for (int index = 0; index < parts.size(); index++) {
            String prefix = parts.size() == 1 ? "" : "（" + (index + 1) + "/" + parts.size() + "）\n";
            sendSystemText(openKfId, externalUserId, inboundMessageId,
                    messageKind + "-" + (index + 1), prefix + parts.get(index));
        }
    }

    private final class ProgressNotice {
        private final Object monitor = new Object();
        private final String openKfId;
        private final String externalUserId;
        private final String inboundMessageId;
        private boolean finished;
        private ScheduledFuture<?> future;

        private ProgressNotice(
                String openKfId, String externalUserId, String inboundMessageId) {
            this.openKfId = openKfId;
            this.externalUserId = externalUserId;
            this.inboundMessageId = inboundMessageId;
        }

        private void start() {
            if (!StringUtils.hasText(properties.getProgressMessage())) {
                return;
            }
            Duration delay = properties.getProgressDelay();
            if (delay == null || delay.isNegative()) {
                delay = Duration.ZERO;
            }
            future = progressScheduler.schedule(() -> {
                synchronized (monitor) {
                    if (finished) {
                        return;
                    }
                    try {
                        sendSystemText(openKfId, externalUserId, inboundMessageId,
                                "progress", properties.getProgressMessage());
                    } catch (Exception ex) {
                        log.warn("event=wecom.kf.progress_failed openKfId={} "
                                        + "externalUserId={} error={}",
                                openKfId, externalUserId, rootMessage(ex));
                    }
                }
            }, Instant.now().plus(delay));
        }

        private void finish() {
            synchronized (monitor) {
                finished = true;
                if (future != null) {
                    future.cancel(false);
                }
            }
        }
    }

    private void markProcessed(
            String messageId, String openKfId, String externalUserId, String messageType) {
        stateMapper.insertProcessed(messageId, blankToNull(openKfId),
                blankToNull(externalUserId), blankToNull(messageType));
        pendingMessageMapper.deletePending(messageId);
        pendingMessageMapper.deleteRetry(messageId);
    }

    private static String replyMessageId(String inboundMessageId, String messageKind) {
        try {
            String idempotencyKey = "final".equals(messageKind)
                    ? inboundMessageId : inboundMessageId + ":" + messageKind;
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(idempotencyKey.getBytes(StandardCharsets.UTF_8));
            return "vr_" + HexFormat.of().formatHex(digest, 0, 14);
        } catch (Exception ex) {
            throw new IllegalStateException("SHA-256 is unavailable", ex);
        }
    }

    static String truncateUtf8(String value, int maxBytes) {
        if (value == null) {
            return "";
        }
        if (value.getBytes(StandardCharsets.UTF_8).length <= maxBytes) {
            return value;
        }
        StringBuilder result = new StringBuilder();
        int bytes = 0;
        for (int offset = 0; offset < value.length();) {
            int codePoint = value.codePointAt(offset);
            String character = new String(Character.toChars(codePoint));
            int length = character.getBytes(StandardCharsets.UTF_8).length;
            if (bytes + length > maxBytes - 3) {
                break;
            }
            result.append(character);
            bytes += length;
            offset += Character.charCount(codePoint);
        }
        return result.append("...").toString();
    }

    static java.util.List<String> splitUtf8(String value, int maxBytes) {
        String source = value == null ? "" : value.strip();
        if (source.getBytes(StandardCharsets.UTF_8).length <= maxBytes) {
            return java.util.List.of(source);
        }
        java.util.List<String> parts = new java.util.ArrayList<>();
        StringBuilder current = new StringBuilder();
        int bytes = 0;
        for (int offset = 0; offset < source.length();) {
            int codePoint = source.codePointAt(offset);
            String character = new String(Character.toChars(codePoint));
            int length = character.getBytes(StandardCharsets.UTF_8).length;
            if (bytes + length > maxBytes && !current.isEmpty()) {
                parts.add(current.toString().strip());
                current.setLength(0);
                bytes = 0;
            }
            current.append(character);
            bytes += length;
            offset += Character.charCount(codePoint);
        }
        if (!current.isEmpty()) {
            parts.add(current.toString().strip());
        }
        return java.util.List.copyOf(parts);
    }

    private static String blankToNull(String value) {
        return StringUtils.hasText(value) ? value : null;
    }

    private static String firstText(String preferred, String fallback) {
        return StringUtils.hasText(preferred) ? preferred : fallback;
    }

    private record HandoffAttempt(boolean assigned, RuntimeException lastError) {
    }

    private static String rootMessage(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        return current.getMessage() == null
                ? current.getClass().getSimpleName() : current.getMessage();
    }

    private static String customerKey(JsonNode message) {
        return message.path("open_kfid").asText("") + ":"
                + message.path("external_userid").asText("");
    }

    private static final class PendingCustomerMessages {
        private final java.util.List<JsonNode> messages = new java.util.ArrayList<>();
        private ScheduledFuture<?> future;
    }
}
