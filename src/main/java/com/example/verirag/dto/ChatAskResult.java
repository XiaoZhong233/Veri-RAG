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
    /** 由意图分类产生的内部动作，不以模型回答文字触发转接。 */
    @com.fasterxml.jackson.annotation.JsonIgnore
    private boolean humanHandoff;
    /** 微信菜单直接使用工具结果，不从模型的自然语言中猜测公寓或查询条件。 */
    @com.fasterxml.jackson.annotation.JsonIgnore
    private com.example.verirag.tool.ToolCallEventContext.Event propertyRecommendation;
    /** 引用列表：title、docId、categoryId、snippet */
    private List<Map<String, Object>> references;
}
