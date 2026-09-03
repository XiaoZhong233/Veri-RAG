package com.example.verirag.integration.wecom;

import com.example.verirag.config.WeComKfProperties;
import com.example.verirag.dto.ChatAskRequest;
import com.example.verirag.dto.ChatAskResult;
import com.example.verirag.mapper.WeComConversationMapper;
import com.example.verirag.mapper.WeComKfStateMapper;
import com.example.verirag.service.ChatService;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 消费微信客服回调通知，通过 sync_msg 拉取消息并调用 Veri-RAG 回复。
 */
@Service
@RequiredArgsConstructor
@Slf4j
@ConditionalOnProperty(prefix = "wecom.kf", name = "enabled", havingValue = "true")
public class WeComKfMessageService {

    private static final int MAX_TEXT_BYTES = 2048;
    private final WeComKfProperties properties;
    private final WeComKfApiClient apiClient;
    private final WeComKfStateMapper stateMapper;
    private final WeComConversationMapper conversationMapper;
    private final ChatService chatService;
    private final ConcurrentHashMap<String, Object> accountLocks = new ConcurrentHashMap<>();

    /** HTTP 回调必须快速返回，具体同步与模型调用放入独立线程池。 */
    @Async("wecomKfExecutor")
    public void handleNotification(String callbackToken, String openKfId) {
        Object lock = accountLocks.computeIfAbsent(openKfId, ignored -> new Object());
        synchronized (lock) {
            try {
                syncAll(callbackToken, openKfId);
            } catch (Exception ex) {
                log.error("event=wecom.kf.sync_failed openKfId={} error={}",
                        openKfId, rootMessage(ex));
            }
        }
    }

    private void syncAll(String callbackToken, String openKfId) {
        String cursor = stateMapper.selectCursor(openKfId);
        boolean hasMore;
        do {
            JsonNode page = apiClient.syncMessages(openKfId, callbackToken, cursor);
            for (JsonNode message : page.path("msg_list")) {
                processMessage(message);
            }
            String nextCursor = page.path("next_cursor").asText("");
            hasMore = page.path("has_more").asInt(0) == 1;
            if (StringUtils.hasText(nextCursor)) {
                stateMapper.upsertCursor(openKfId, nextCursor);
                cursor = nextCursor;
            } else if (hasMore) {
                throw new IllegalStateException("WeCom sync_msg returned has_more=1 without next_cursor");
            }
        } while (hasMore);
    }

    private void processMessage(JsonNode message) {
        String messageId = message.path("msgid").asText("");
        if (!StringUtils.hasText(messageId) || stateMapper.isProcessed(messageId)) {
            return;
        }
        String openKfId = message.path("open_kfid").asText("");
        String externalUserId = message.path("external_userid").asText("");
        String messageType = message.path("msgtype").asText("");
        int origin = message.path("origin").asInt(0);

        // 只回答微信客户发来的消息；系统事件和接待人员消息仅记为已消费。
        if (origin != 3 || !StringUtils.hasText(openKfId)
                || !StringUtils.hasText(externalUserId)) {
            markProcessed(messageId, openKfId, externalUserId, messageType);
            return;
        }

        int serviceState = apiClient.getServiceState(openKfId, externalUserId);
        // 2=待接入池、3=人工接待、4=已结束；这些状态下机器人不能也不应抢答。
        if (serviceState == 2 || serviceState == 3 || serviceState == 4) {
            log.info("event=wecom.kf.message_skipped reason=service_state state={} "
                            + "openKfId={} externalUserId={}",
                    serviceState, openKfId, externalUserId);
            markProcessed(messageId, openKfId, externalUserId, messageType);
            return;
        }
        if (serviceState == 0) {
            apiClient.transitionToAssistant(openKfId, externalUserId);
        } else if (serviceState != 1) {
            throw new IllegalStateException("Unknown WeCom KF service_state: " + serviceState);
        }

        if (!"text".equals(messageType)) {
            apiClient.sendText(openKfId, externalUserId, replyMessageId(messageId),
                    truncateUtf8(properties.getUnsupportedMessage(), MAX_TEXT_BYTES));
            markProcessed(messageId, openKfId, externalUserId, messageType);
            return;
        }

        String question = message.path("text").path("content").asText("").trim();
        if (!StringUtils.hasText(question)) {
            markProcessed(messageId, openKfId, externalUserId, messageType);
            return;
        }
        answerQuestion(messageId, openKfId, externalUserId, question);
        markProcessed(messageId, openKfId, externalUserId, messageType);
    }

    private void answerQuestion(
            String messageId, String openKfId, String externalUserId, String question) {
        String channelId = "kf:" + openKfId;
        ChatAskRequest request = new ChatAskRequest();
        request.setQuestion(question);
        request.setSessionId(conversationMapper.selectSessionId(channelId, externalUserId));
        try {
            ChatAskResult result = chatService.ask(properties.getUserId(), request);
            conversationMapper.upsertSessionId(
                    channelId, externalUserId, properties.getUserId(), result.getSessionId());
            apiClient.sendText(openKfId, externalUserId, replyMessageId(messageId),
                    truncateUtf8(result.getAnswer(), MAX_TEXT_BYTES));
            log.info("event=wecom.kf.answer_sent openKfId={} externalUserId={} sessionId={}",
                    openKfId, externalUserId, result.getSessionId());
        } catch (Exception ex) {
            log.warn("event=wecom.kf.answer_failed openKfId={} externalUserId={} error={}",
                    openKfId, externalUserId, rootMessage(ex));
            apiClient.sendText(openKfId, externalUserId, replyMessageId(messageId),
                    truncateUtf8(properties.getErrorMessage(), MAX_TEXT_BYTES));
        }
    }

    private void markProcessed(
            String messageId, String openKfId, String externalUserId, String messageType) {
        stateMapper.insertProcessed(messageId, blankToNull(openKfId),
                blankToNull(externalUserId), blankToNull(messageType));
    }

    private static String replyMessageId(String inboundMessageId) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(inboundMessageId.getBytes(StandardCharsets.UTF_8));
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

    private static String blankToNull(String value) {
        return StringUtils.hasText(value) ? value : null;
    }

    private static String rootMessage(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        return current.getMessage() == null
                ? current.getClass().getSimpleName() : current.getMessage();
    }
}
