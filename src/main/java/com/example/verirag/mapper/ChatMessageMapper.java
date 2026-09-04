package com.example.verirag.mapper;

import com.example.verirag.entity.ChatMessage;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.Map;

/**
 * 聊天消息 Mapper。
 */
@Mapper
public interface ChatMessageMapper {

    int insert(ChatMessage row);

    List<ChatMessage> listBySessionId(@Param("sessionId") Long sessionId);

    /** 仅取最近消息，避免长会话每轮都全表读取后再在 Java 中截断。 */
    List<ChatMessage> listRecentBySessionId(@Param("sessionId") Long sessionId,
                                             @Param("limit") int limit);

    int deleteBySessionId(@Param("sessionId") Long sessionId);

    long countAssistantToday();

    List<Map<String, Object>> countAssistantByDayLast7();
}
