package com.example.verirag.integration.wecom;

import com.example.verirag.config.WeComBotProperties;
import com.example.verirag.dto.ChatAskRequest;
import com.example.verirag.dto.ChatStreamEvent;
import com.example.verirag.mapper.WeComConversationMapper;
import com.example.verirag.service.ChatService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;

/**
 * 企业微信智能机器人 WebSocket 长连接客户端。
 *
 * <p>第一阶段支持文本消息、进入会话欢迎语、流式回复、心跳、自动重连、
 * 消息排重，以及企业微信会话到本地 RAG 会话的持久化映射。</p>
 *
 * <p>它是“渠道适配层”，只负责企业微信协议与本项目聊天协议之间的转换：
 * 企业微信帧 -> {@link ChatAskRequest} -> {@link ChatService} ->
 * {@link ChatStreamEvent} -> 企业微信流式回复帧。RAG 检索、意图识别、
 * Tool Calling、聊天记录落库等业务仍由 {@code ChatService} 负责。</p>
 *
 * <p>连接生命周期由 Spring {@link SmartLifecycle} 管理。应用启动时建立并认证
 * WebSocket，应用停止时关闭连接和线程池。企业微信规定同一个机器人只能保留
 * 一个有效长连接，因此该组件只适合单实例运行；多实例部署需要额外的主节点选举。</p>
 */
@Component
@ConditionalOnProperty(prefix = "wecom.bot", name = "enabled", havingValue = "true")
@Slf4j
public class WeComBotClient implements SmartLifecycle {

    private static final String SUBSCRIBE_COMMAND = "aibot_subscribe";
    private static final String MESSAGE_CALLBACK_COMMAND = "aibot_msg_callback";
    private static final String EVENT_CALLBACK_COMMAND = "aibot_event_callback";
    private static final String RESPOND_MESSAGE_COMMAND = "aibot_respond_msg";
    private static final String RESPOND_WELCOME_COMMAND = "aibot_respond_welcome_msg";
    private static final String ENTER_CHAT_EVENT = "enter_chat";
    private static final String DISCONNECTED_EVENT = "disconnected_event";
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(15);
    private static final Duration DEDUPLICATION_TTL = Duration.ofHours(25);
    private static final Pattern LEADING_MENTION = Pattern.compile("^\\s*@\\S+\\s*");
    private static final int MAX_REFERENCE_TITLES = 5;

    /** 企业微信连接参数、机器人凭证、心跳及流式刷新间隔。 */
    private final WeComBotProperties properties;
    /** 复用 Web UI 背后的完整 RAG/Tool/会话记忆能力。 */
    private final ChatService chatService;
    /** 将企业微信会话稳定映射到本地 t_chat_session，保留多轮上下文。 */
    private final WeComConversationMapper conversationMapper;
    /** 企业微信 WebSocket 帧的 JSON 编解码器。 */
    private final ObjectMapper objectMapper;

    // 连接与线程模型：
    // - HttpClient 管理底层 WebSocket；
    // - scheduler 串行执行心跳、重连和流式刷新定时任务；
    // - messageExecutor 使用虚拟线程处理不同会话，避免一次 LLM 调用阻塞收帧线程。
    private final HttpClient httpClient;
    private final ScheduledExecutorService scheduler;
    private final ExecutorService messageExecutor;

    /** 当前有效连接。AtomicReference 便于重连时安全替换旧连接。 */
    private final AtomicReference<WebSocket> webSocket = new AtomicReference<>();
    private final AtomicBoolean running = new AtomicBoolean();
    private final AtomicBoolean authenticated = new AtomicBoolean();
    /** 防止 onError、onClose 等多个路径重复安排重连任务。 */
    private final AtomicBoolean reconnectScheduled = new AtomicBoolean();
    private final AtomicInteger reconnectAttempt = new AtomicInteger();
    /** 记录本次订阅 req_id，用于从普通命令响应中识别认证响应。 */
    private final AtomicReference<String> subscribeRequestId = new AtomicReference<>();
    /** msgid -> 首次处理时间，用于拦截企业微信可能重投的同一条消息。 */
    private final Map<String, Instant> handledMessageIds = new ConcurrentHashMap<>();
    /**
     * 每个会话自己的任务尾节点。同一群聊/单聊按顺序回答，
     * 不同会话仍可借助虚拟线程并行处理。
     */
    private final Map<String, CompletableFuture<Void>> conversationTails = new ConcurrentHashMap<>();
    private final Object outboundLock = new Object();

    /**
     * JDK WebSocket 同一时刻只允许一个未完成的文本发送。
     * outboundTail 把所有出站帧串成 CompletableFuture 链，确保严格有序。
     */
    private CompletableFuture<?> outboundTail = CompletableFuture.completedFuture(null);
    private volatile ScheduledFuture<?> heartbeatTask;

    public WeComBotClient(
            WeComBotProperties properties,
            ChatService chatService,
            WeComConversationMapper conversationMapper,
            ObjectMapper objectMapper) {
        this.properties = properties;
        this.chatService = chatService;
        this.conversationMapper = conversationMapper;
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(CONNECT_TIMEOUT)
                .build();
        this.scheduler = Executors.newSingleThreadScheduledExecutor(
                Thread.ofPlatform().name("wecom-bot-scheduler-", 0).factory());
        this.messageExecutor = Executors.newVirtualThreadPerTaskExecutor();
    }

    @Override
    public void start() {
        if (!running.compareAndSet(false, true)) {
            return;
        }
        validateConfiguration();
        log.info("event=wecom.bot.start botId={} endpoint={}",
                properties.getBotId(), properties.getWebsocketUrl());
        connect();
    }

    @Override
    public void stop() {
        if (!running.compareAndSet(true, false)) {
            return;
        }
        authenticated.set(false);
        cancelHeartbeat();
        WebSocket socket = webSocket.getAndSet(null);
        if (socket != null) {
            socket.sendClose(WebSocket.NORMAL_CLOSURE, "application stopping");
        }
        scheduler.shutdownNow();
        messageExecutor.shutdownNow();
        log.info("event=wecom.bot.stop");
    }

    @Override
    public boolean isRunning() {
        return running.get();
    }

    @Override
    public boolean isAutoStartup() {
        return true;
    }

    @Override
    public int getPhase() {
        // 尽量等业务 Bean 和数据源准备好后再连接；停机时则优先断开外部入口。
        return Integer.MAX_VALUE;
    }

    /**
     * 启动阶段快速校验必需参数，避免程序看似启动成功、实际一直认证重试。
     */
    private void validateConfiguration() {
        if (!StringUtils.hasText(properties.getBotId())) {
            throw new IllegalStateException("wecom.bot.bot-id must not be blank");
        }
        if (!StringUtils.hasText(properties.getSecret())) {
            throw new IllegalStateException("wecom.bot.secret must not be blank");
        }
        if (properties.getWebsocketUrl() == null
                || !"wss".equalsIgnoreCase(properties.getWebsocketUrl().getScheme())) {
            throw new IllegalStateException("wecom.bot.websocket-url must use wss");
        }
        if (properties.getUserId() <= 0) {
            throw new IllegalStateException("wecom.bot.user-id must be positive");
        }
    }

    /**
     * 只完成 WebSocket 握手。握手成功后，{@link SocketListener#onOpen(WebSocket)}
     * 还会发送 aibot_subscribe，只有订阅响应成功才算真正可用。
     */
    private void connect() {
        if (!running.get()) {
            return;
        }
        reconnectScheduled.set(false);
        log.info("event=wecom.bot.connecting attempt={}", reconnectAttempt.get() + 1);
        httpClient.newWebSocketBuilder()
                .connectTimeout(CONNECT_TIMEOUT)
                .buildAsync(properties.getWebsocketUrl(), new SocketListener())
                .whenComplete((socket, error) -> {
                    if (error != null) {
                        log.warn("event=wecom.bot.connect_failed error={}", rootMessage(error));
                        scheduleReconnect();
                    }
                });
    }

    /**
     * 使用 BotID 和 Secret 订阅机器人消息。
     * Secret 只进入出站 JSON，不会写入应用日志。
     */
    private void subscribe(WebSocket socket) {
        String requestId = newRequestId("subscribe");
        subscribeRequestId.set(requestId);

        ObjectNode body = objectMapper.createObjectNode();
        body.put("bot_id", properties.getBotId());
        body.put("secret", properties.getSecret());
        sendCommand(socket, SUBSCRIBE_COMMAND, requestId, body);
    }

    /**
     * 所有完整文本帧的统一入口。
     *
     * <p>没有 cmd 的帧通常是订阅、心跳或回复消息的响应；有 cmd 的帧才是
     * 企业微信主动推送的消息/事件回调。</p>
     */
    private void processIncoming(String payload) {
        final JsonNode frame;
        try {
            frame = objectMapper.readTree(payload);
        } catch (Exception error) {
            log.warn("event=wecom.bot.invalid_frame error={}", rootMessage(error));
            return;
        }

        String requestId = frame.path("headers").path("req_id").asText("");
        if (requestId.equals(subscribeRequestId.get())) {
            processSubscriptionResponse(frame);
            return;
        }

        String command = frame.path("cmd").asText("");
        switch (command) {
            case MESSAGE_CALLBACK_COMMAND -> enqueueMessage(frame);
            case EVENT_CALLBACK_COMMAND -> processEvent(frame);
            case "" -> {
                if (frame.path("errcode").asInt(0) != 0) {
                    log.warn("event=wecom.bot.command_failed reqId={} errcode={} errmsg={}",
                            requestId, frame.path("errcode").asInt(),
                            frame.path("errmsg").asText(""));
                }
            }
            default -> log.debug("event=wecom.bot.command_ignored command={}", command);
        }
    }

    /**
     * 订阅成功后才启动心跳，并清零指数退避次数。
     */
    private void processSubscriptionResponse(JsonNode frame) {
        int errorCode = frame.path("errcode").asInt(-1);
        if (errorCode != 0) {
            log.error("event=wecom.bot.authentication_failed errcode={} errmsg={}",
                    errorCode, frame.path("errmsg").asText(""));
            WebSocket socket = webSocket.getAndSet(null);
            if (socket != null) {
                socket.sendClose(WebSocket.NORMAL_CLOSURE, "authentication failed");
            }
            scheduleReconnect();
            return;
        }

        authenticated.set(true);
        reconnectAttempt.set(0);
        log.info("event=wecom.bot.authenticated botId={}", properties.getBotId());
        startHeartbeat();
    }

    /**
     * 对文本消息做类型过滤、msgid 排重和会话内串行化，然后交给虚拟线程处理。
     *
     * <p>会话内串行化很重要：同一用户连续发两条消息时，第二条需要等第一条
     * 完成落库后再读取历史，否则两轮会话上下文可能交叉。</p>
     */
    private void enqueueMessage(JsonNode frame) {
        JsonNode body = frame.path("body");
        if (!"text".equals(body.path("msgtype").asText())) {
            log.info("event=wecom.bot.message_ignored msgType={}",
                    body.path("msgtype").asText(""));
            return;
        }

        String messageId = body.path("msgid").asText("");
        if (isDuplicate(messageId)) {
            log.info("event=wecom.bot.message_duplicate msgId={}", messageId);
            return;
        }

        String conversationKey = conversationKey(body);
        if (!StringUtils.hasText(conversationKey)) {
            log.warn("event=wecom.bot.message_missing_conversation msgId={}", messageId);
            return;
        }

        CompletableFuture<Void> next = conversationTails.compute(conversationKey, (key, previous) -> {
            CompletableFuture<Void> base = previous == null
                    ? CompletableFuture.completedFuture(null)
                    : previous.exceptionally(error -> null);
            return base.thenRunAsync(() -> handleTextMessage(frame, key), messageExecutor);
        });
        next.whenComplete((ignored, error) -> {
            conversationTails.remove(conversationKey, next);
            if (error != null) {
                log.warn("event=wecom.bot.message_failed conversation={} error={}",
                        conversationKey, rootMessage(error));
            }
        });
    }

    /**
     * 把一条企业微信文本消息转换为现有 ChatService 的流式调用。
     *
     * <ol>
     *   <li>先回复“正在思考”，让用户及时看到机器人已接收请求；</li>
     *   <li>根据 conversationKey 找回本地 sessionId；</li>
     *   <li>调用 ChatService.streamAsk，消费 meta/chunk/done/error；</li>
     *   <li>将增量 chunk 聚合成企业微信要求的“完整内容刷新”。</li>
     * </ol>
     */
    private void handleTextMessage(JsonNode frame, String conversationKey) {
        JsonNode body = frame.path("body");
        String requestId = frame.path("headers").path("req_id").asText("");
        String question = normalizeQuestion(
                body.path("text").path("content").asText(""),
                properties.getDisplayName(),
                "group".equals(body.path("chattype").asText("")));
        if (!StringUtils.hasText(question)) {
            // 企业微信不会把群聊中未 @机器人的上一条普通消息推给机器人。
            // 因此只有 mention 而没有正文时，不能猜测上文，直接提示用户把问题放在同一条消息里。
            sendStream(requestId, newRequestId("stream"),
                    properties.getEmptyQuestionMessage(), true);
            return;
        }

        String streamId = newRequestId("stream");
        StreamReply reply = new StreamReply(requestId, streamId);
        reply.start();

        ChatAskRequest request = new ChatAskRequest();
        request.setQuestion(question);
        request.setSessionId(conversationMapper.selectSessionId(
                properties.getBotId(), conversationKey));

        log.info("event=wecom.bot.question conversation={} sessionId={} chars={}",
                conversationKey, request.getSessionId(), question.length());
        try {
            chatService.streamAsk(properties.getUserId(), request)
                    .doOnNext(event -> processChatEvent(
                            event, conversationKey, reply))
                    .then()
                    .block();
            if (!reply.isFinished()) {
                reply.finish(null);
            }
        } catch (Exception error) {
            log.warn("event=wecom.bot.answer_failed conversation={} error={}",
                    conversationKey, rootMessage(error));
            reply.fail("抱歉，处理问题时出现异常，请稍后再试。");
        }
    }

    /**
     * 将项目内部 SSE 事件翻译成企业微信动作。
     *
     * <p>meta 保存新建的本地 sessionId；chunk 累积回答；done 发送最终内容与引用；
     * error 结束流并展示友好错误。意图识别和 Tool 进度目前不发送到企业微信。</p>
     */
    private void processChatEvent(
            ChatStreamEvent event,
            String conversationKey,
            StreamReply reply) {
        switch (event.getType()) {
            case "meta" -> {
                if (event.getSessionId() != null) {
                    conversationMapper.upsertSessionId(
                            properties.getBotId(), conversationKey,
                            properties.getUserId(), event.getSessionId());
                }
            }
            case "chunk" -> reply.append(event.getContent());
            case "done" -> reply.finish(event.getReferences());
            case "error" -> reply.fail(event.getContent());
            default -> {
                // intent/tool 进度只用于 Web UI，不覆盖企业微信中的答案消息。
            }
        }
    }

    /**
     * 处理非文本事件。当前接入进入会话欢迎语，并记录“连接被新实例顶替”事件。
     */
    private void processEvent(JsonNode frame) {
        String eventType = frame.path("body").path("event")
                .path("eventtype").asText("");
        if (ENTER_CHAT_EVENT.equals(eventType)) {
            ObjectNode body = objectMapper.createObjectNode();
            body.put("msgtype", "text");
            body.putObject("text").put("content", properties.getWelcomeMessage());
            sendCommand(RESPOND_WELCOME_COMMAND,
                    frame.path("headers").path("req_id").asText(""), body);
            return;
        }
        if (DISCONNECTED_EVENT.equals(eventType)) {
            log.warn("event=wecom.bot.replaced reason=new_connection");
            return;
        }
        log.debug("event=wecom.bot.event_ignored eventType={}", eventType);
    }

    /**
     * 企业微信的 msgid 是消息唯一标识。保留超过官方可回复窗口的 25 小时，
     * 避免重投消息再次触发一次昂贵的 LLM 调用。
     */
    private boolean isDuplicate(String messageId) {
        if (!StringUtils.hasText(messageId)) {
            return false;
        }
        Instant now = Instant.now();
        Instant cutoff = now.minus(DEDUPLICATION_TTL);
        if (handledMessageIds.size() > 1000) {
            handledMessageIds.entrySet().removeIf(entry -> entry.getValue().isBefore(cutoff));
        }
        return handledMessageIds.putIfAbsent(messageId, now) != null;
    }

    /**
     * 生成稳定的外部会话键：
     * 群聊使用 chatid，单聊使用发送者 userid，避免两类 ID 发生碰撞。
     */
    static String conversationKey(JsonNode body) {
        String chatType = body.path("chattype").asText("");
        if ("group".equals(chatType)) {
            String chatId = body.path("chatid").asText("");
            return StringUtils.hasText(chatId) ? "group:" + chatId : null;
        }
        String userId = body.path("from").path("userid").asText("");
        return StringUtils.hasText(userId) ? "single:" + userId : null;
    }

    /**
     * 群聊文本通常以“@机器人”开头，进入模型前去掉该渠道噪声。
     *
     * <p>优先按配置的完整展示名称匹配，以支持“londonist 助手”这类带空格名称；
     * displayName 未配置或消息格式不匹配时，再用单词型 mention 做兼容兜底。
     * 单聊消息没有强制 mention，保持原文即可。</p>
     */
    static String normalizeQuestion(
            String content,
            String displayName,
            boolean groupChat) {
        if (!StringUtils.hasText(content)) {
            return "";
        }
        String normalized = content.strip();
        if (!groupChat) {
            return normalized;
        }

        if (StringUtils.hasText(displayName)) {
            String mention = "@" + displayName.strip();
            if (normalized.startsWith(mention)) {
                return normalized.substring(mention.length()).strip();
            }
        }
        return LEADING_MENTION.matcher(normalized).replaceFirst("").strip();
    }

    /**
     * 认证后按配置周期发送应用层 ping。最低限制为 5 秒，防止误配置造成高频请求。
     */
    private void startHeartbeat() {
        cancelHeartbeat();
        long intervalMillis = Math.max(
                properties.getHeartbeatInterval().toMillis(), 5000);
        heartbeatTask = scheduler.scheduleAtFixedRate(() -> {
            if (authenticated.get()) {
                sendCommand("ping", newRequestId("ping"), null);
            }
        }, intervalMillis, intervalMillis, TimeUnit.MILLISECONDS);
    }

    private void cancelHeartbeat() {
        ScheduledFuture<?> task = heartbeatTask;
        heartbeatTask = null;
        if (task != null) {
            task.cancel(false);
        }
    }

    /**
     * 使用 1、2、4、8、16、30 秒的指数退避重连，最大等待 30 秒。
     */
    private void scheduleReconnect() {
        if (!running.get() || !reconnectScheduled.compareAndSet(false, true)) {
            return;
        }
        authenticated.set(false);
        cancelHeartbeat();
        int attempt = reconnectAttempt.incrementAndGet();
        long delaySeconds = Math.min(1L << Math.min(attempt - 1, 5), 30L);
        log.info("event=wecom.bot.reconnect_scheduled attempt={} delay={}s",
                attempt, delaySeconds);
        scheduler.schedule(this::connect, delaySeconds, TimeUnit.SECONDS);
    }

    private void sendCommand(String command, String requestId, JsonNode body) {
        WebSocket socket = webSocket.get();
        if (socket == null) {
            log.warn("event=wecom.bot.send_skipped command={} reason=disconnected", command);
            return;
        }
        sendCommand(socket, command, requestId, body);
    }

    /**
     * 构造企业微信统一命令帧：cmd + headers.req_id + 可选 body。
     */
    private void sendCommand(
            WebSocket socket,
            String command,
            String requestId,
            JsonNode body) {
        ObjectNode frame = objectMapper.createObjectNode();
        frame.put("cmd", command);
        frame.putObject("headers").put("req_id", requestId);
        if (body != null) {
            frame.set("body", body);
        }
        sendFrame(socket, frame);
    }

    /**
     * 串行发送 JSON 帧。
     *
     * <p>任务执行前再次比较 expectedSocket 与当前连接，防止重连后把旧连接队列中的
     * 回答误发到已经失效的 WebSocket。</p>
     */
    private void sendFrame(WebSocket expectedSocket, JsonNode frame) {
        final String payload;
        try {
            payload = objectMapper.writeValueAsString(frame);
        } catch (Exception error) {
            throw new IllegalStateException("Failed to serialize WeCom frame", error);
        }

        synchronized (outboundLock) {
            outboundTail = outboundTail
                    .exceptionally(error -> null)
                    .thenCompose(ignored -> {
                        if (expectedSocket != webSocket.get()) {
                            return CompletableFuture.completedFuture(null);
                        }
                        return expectedSocket.sendText(payload, true);
                    })
                    .whenComplete((ignored, error) -> {
                        if (error != null) {
                            log.warn("event=wecom.bot.send_failed command={} error={}",
                                    frame.path("cmd").asText(""), rootMessage(error));
                        }
                    });
        }
    }

    /**
     * 发送或刷新一条企业微信流式消息。
     *
     * <p>requestId 必须始终透传原消息回调的 req_id，用于关联本轮回复；
     * streamId 标识企业微信界面上的同一条消息；finish=true 后不可继续更新。</p>
     */
    private void sendStream(
            String requestId,
            String streamId,
            String content,
            boolean finish) {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("msgtype", "stream");
        ObjectNode stream = body.putObject("stream");
        stream.put("id", streamId);
        stream.put("finish", finish);
        stream.put("content", content);
        sendCommand(RESPOND_MESSAGE_COMMAND, requestId, body);
    }

    private static String newRequestId(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().replace("-", "");
    }

    private static String rootMessage(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        String message = current.getMessage();
        return current.getClass().getSimpleName()
                + (StringUtils.hasText(message) ? ": " + message : "");
    }

    /**
     * 企业微信界面只展示引用标题，避免把完整检索 Chunk 再次发给用户。
     */
    private static String appendReferences(
            String answer,
            List<Map<String, Object>> references) {
        if (references == null || references.isEmpty()) {
            return answer;
        }
        StringBuilder result = new StringBuilder(answer);
        result.append("\n\n参考资料：");
        int count = 0;
        for (Map<String, Object> reference : references) {
            Object title = reference.get("title");
            if (title == null || !StringUtils.hasText(title.toString())) {
                continue;
            }
            result.append("\n").append(++count).append(". ").append(title);
            if (count >= MAX_REFERENCE_TITLES) {
                break;
            }
        }
        return result.toString();
    }

    /**
     * 一次问答对应的流式回复状态机。
     *
     * <p>Spring AI 给出的 chunk 是增量文本，而企业微信刷新接口要求每次传入
     * 截至当前的完整文本，所以这里用 StringBuilder 聚合。刷新任务按配置节流，
     * 避免每个 token 都产生一次 WebSocket 请求。</p>
     */
    private final class StreamReply {
        private final String requestId;
        private final String streamId;
        private final StringBuilder content = new StringBuilder();
        private boolean finished;
        private boolean updateScheduled;

        private StreamReply(String requestId, String streamId) {
            this.requestId = requestId;
            this.streamId = streamId;
        }

        private synchronized void start() {
            // 首次发送创建流式消息；后续沿用同一 streamId 即为刷新。
            sendStream(requestId, streamId, "正在思考，请稍候…", false);
        }

        private synchronized void append(String chunk) {
            if (finished || !StringUtils.hasText(chunk)) {
                return;
            }
            content.append(chunk);
            if (!updateScheduled) {
                updateScheduled = true;
                scheduler.schedule(this::flushUpdate,
                        Math.max(properties.getStreamUpdateInterval().toMillis(), 500),
                        TimeUnit.MILLISECONDS);
            }
        }

        private synchronized void flushUpdate() {
            updateScheduled = false;
            if (!finished && !content.isEmpty()) {
                sendStream(requestId, streamId, content.toString(), false);
            }
        }

        private synchronized void finish(List<Map<String, Object>> references) {
            if (finished) {
                return;
            }
            finished = true;
            updateScheduled = false;
            String answer = content.toString().strip();
            if (answer.isEmpty()) {
                answer = "已完成处理，但没有生成可展示的回答。";
            }
            sendStream(requestId, streamId,
                    appendReferences(answer, references), true);
        }

        private synchronized void fail(String message) {
            if (finished) {
                return;
            }
            String friendlyMessage = StringUtils.hasText(message)
                    ? message.strip()
                    : "处理问题时出现异常，请稍后再试。";
            if (!content.isEmpty()) {
                content.append("\n\n> ").append(friendlyMessage);
            } else {
                content.append(friendlyMessage);
            }
            finish(null);
        }

        private synchronized boolean isFinished() {
            return finished;
        }
    }

    /**
     * JDK WebSocket 回调适配器。
     *
     * <p>onText 可能分多段到达，因此先放入 textBuffer，last=true 后才解析 JSON。
     * 每次回调后调用 request(1) 表示继续接收下一条消息。</p>
     */
    private final class SocketListener implements WebSocket.Listener {
        private final StringBuilder textBuffer = new StringBuilder();

        @Override
        public void onOpen(WebSocket socket) {
            WebSocket previous = webSocket.getAndSet(socket);
            if (previous != null && previous != socket) {
                previous.sendClose(WebSocket.NORMAL_CLOSURE, "replaced locally");
            }
            authenticated.set(false);
            log.info("event=wecom.bot.socket_open");
            socket.request(1);
            subscribe(socket);
        }

        @Override
        public CompletionStage<?> onText(
                WebSocket socket,
                CharSequence data,
                boolean last) {
            textBuffer.append(data);
            if (last) {
                String payload = textBuffer.toString();
                textBuffer.setLength(0);
                processIncoming(payload);
            }
            socket.request(1);
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletionStage<?> onClose(
                WebSocket socket,
                int statusCode,
                String reason) {
            webSocket.compareAndSet(socket, null);
            authenticated.set(false);
            cancelHeartbeat();
            log.warn("event=wecom.bot.socket_closed status={} reason={}",
                    statusCode, reason);
            scheduleReconnect();
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public void onError(WebSocket socket, Throwable error) {
            webSocket.compareAndSet(socket, null);
            authenticated.set(false);
            cancelHeartbeat();
            log.warn("event=wecom.bot.socket_error error={}", rootMessage(error));
            scheduleReconnect();
        }
    }
}
