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

    int deleteBySessionId(@Param("sessionId") Long sessionId);

    long countAssistantToday();

    List<Map<String, Object>> countAssistantByDayLast7();
}
