package com.example.verirag.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * 问答接口返回：助手回复 + 引用片段。
 */
@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ChatAskResult {
    private Long sessionId;
    private String answer;
    /** 引用列表：title、docId、categoryId、snippet */
    private List<Map<String, Object>> references;
}
