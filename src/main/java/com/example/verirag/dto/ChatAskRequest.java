package com.example.verirag.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.util.List;

/**
 * 问答请求。
 */
@Data
public class ChatAskRequest {
    @NotBlank(message = "问题不能为空")
    private String question;
    /** 已有会话 ID；为空则新建会话 */
    private Long sessionId;
    /** 限定检索分类，空表示全库 */
    private List<Long> categoryIds;
    /** 是否要求模型输出适合纯文本聊天渠道的内容；网页版默认保留 Markdown。 */
    private boolean plainText;
}
