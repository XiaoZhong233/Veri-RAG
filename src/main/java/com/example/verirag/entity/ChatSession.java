package com.example.verirag.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 聊天会话，对应 t_chat_session。
 */
@Data
@TableName("t_chat_session")
public class ChatSession {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long userId;
    private String title;
    /** 已压缩的早期会话记忆；完整聊天记录仍保存在 t_chat_message。 */
    private String memorySummary;
    /** 已被压缩进 memorySummary 的消息数量。 */
    private Integer summarizedMessageCount;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
