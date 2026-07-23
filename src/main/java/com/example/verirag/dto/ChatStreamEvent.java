package com.example.verirag.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Data;

import java.util.List;
import java.util.Map;

/** SSE 问答流事件。type 为 meta、chunk 或 done。 */
@Data
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ChatStreamEvent {
    private String type;
    private Long sessionId;
    private String content;
    private List<Map<String, Object>> references;

    public static ChatStreamEvent meta(Long sessionId) {
        return new ChatStreamEvent("meta", sessionId, null, null);
    }

    public static ChatStreamEvent chunk(String content) {
        return new ChatStreamEvent("chunk", null, content, null);
    }

    public static ChatStreamEvent done(Long sessionId, List<Map<String, Object>> references) {
        return new ChatStreamEvent("done", sessionId, null, references);
    }
}
