package com.example.verirag.controller;

import com.example.verirag.common.R;
import com.example.verirag.dto.ChatAskRequest;
import com.example.verirag.dto.ChatAskResult;
import com.example.verirag.dto.ChatStreamEvent;
import com.example.verirag.entity.ChatMessage;
import com.example.verirag.entity.ChatSession;
import com.example.verirag.service.ChatService;
import com.example.verirag.util.SecurityUtils;
import io.micrometer.context.ContextSnapshot;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import reactor.core.publisher.Flux;

import java.util.List;

/**
 * 知识库对话与历史。
 */
@RestController
@RequestMapping("/api/chat")
@RequiredArgsConstructor
@Slf4j
public class ChatController {

    private final ChatService chatService;

    /**
     * 提交问题，返回助手回答与引用。
     */
    @PostMapping("/ask")
    public R<ChatAskResult> ask(@Valid @RequestBody ChatAskRequest req) throws Exception {
        var u = SecurityUtils.requireUser();
        return R.ok(chatService.ask(u.getUserId(), req));
    }

    /**
     * 流式问答：发送 meta、chunk、done、error 以及 Tool 进度 SSE 事件。
     */
    @PostMapping(value = "/ask/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<ChatStreamEvent>> streamAsk(@Valid @RequestBody ChatAskRequest req) {
        var u = SecurityUtils.requireUser();
        // Spring MVC 对 Flux 的订阅可能发生在原 HTTP 线程之外。此时先捕获 trace/MDC，
        // 再放入 Reactor Context，结合 spring.reactor.context-propagation=auto 在异步线程恢复。
        ContextSnapshot snapshot = ContextSnapshot.captureAll();
        log.info("event=rag.stream.request.accepted");
        return chatService.streamAsk(u.getUserId(), req)
                .map(event -> ServerSentEvent.builder(event).event(event.getType()).build())
                .contextWrite(snapshot::updateContext);
    }

    /**
     * 当前用户的会话列表。
     */
    @GetMapping("/sessions")
    public R<List<ChatSession>> sessions() {
        var u = SecurityUtils.requireUser();
        return R.ok(chatService.listSessions(u.getUserId()));
    }

    /**
     * 会话消息列表。
     */
    @GetMapping("/sessions/{sessionId}/messages")
    public R<List<ChatMessage>> messages(@PathVariable Long sessionId) {
        var u = SecurityUtils.requireUser();
        return R.ok(chatService.listMessages(u.getUserId(), sessionId));
    }

    /**
     * 删除会话及消息。
     */
    @DeleteMapping("/sessions/{sessionId}")
    public R<Void> deleteSession(@PathVariable Long sessionId) {
        var u = SecurityUtils.requireUser();
        chatService.deleteSession(u.getUserId(), sessionId);
        return R.ok();
    }
}
