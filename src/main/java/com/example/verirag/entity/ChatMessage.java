package com.example.verirag.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 聊天消息，对应 t_chat_message。
 */
@Data
@TableName("t_chat_message")
public class ChatMessage {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long sessionId;
    /** USER / ASSISTANT */
    private String role;
    private String content;
    /** JSON 字符串：引用列表 */
    private String refs;
    private LocalDateTime createTime;
}
