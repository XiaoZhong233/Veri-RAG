package com.example.verirag.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * SSE 问答流事件。type 支持 meta、intent_start、intent_done、route_start、
 * chunk、tool_start、tool_done、tool_error、done 和 error。
 */
@Data
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ChatStreamEvent {
    private String type;
    private Long sessionId;
    private String content;
    private List<Map<String, Object>> references;
    private String toolName;
    private String intent;

    public static ChatStreamEvent meta(Long sessionId) {
        return new ChatStreamEvent("meta", sessionId, null, null, null, null);
    }

    public static ChatStreamEvent chunk(String content) {
        return new ChatStreamEvent("chunk", null, content, null, null, null);
    }

    public static ChatStreamEvent progress(String type, String content) {
        return new ChatStreamEvent(type, null, content, null, null, null);
    }

    public static ChatStreamEvent intentDone(String intent, String content) {
        return new ChatStreamEvent("intent_done", null, content, null, null, intent);
    }

    public static ChatStreamEvent toolStart(String toolName, String content) {
        return new ChatStreamEvent("tool_start", null, content, null, toolName, null);
    }

    public static ChatStreamEvent toolDone(String toolName, String content) {
        return new ChatStreamEvent("tool_done", null, content, null, toolName, null);
    }

    public static ChatStreamEvent toolError(String toolName, String content) {
        return new ChatStreamEvent("tool_error", null, content, null, toolName, null);
    }

    public static ChatStreamEvent done(Long sessionId, List<Map<String, Object>> references) {
        return new ChatStreamEvent("done", sessionId, null, references, null, null);
    }

    public static ChatStreamEvent error(Long sessionId, String message) {
        return new ChatStreamEvent("error", sessionId, message, null, null, null);
    }
}
