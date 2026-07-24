package com.example.verirag.mapper;

import com.example.verirag.entity.ChatSession;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 会话 Mapper。
 */
@Mapper
public interface ChatSessionMapper {

    int insert(ChatSession row);

    int updateTitle(@Param("id") Long id, @Param("title") String title);

    int touchUpdateTime(@Param("id") Long id);

    int updateMemorySummary(@Param("id") Long id,
                            @Param("memorySummary") String memorySummary,
                            @Param("summarizedMessageCount") int summarizedMessageCount);

    ChatSession selectById(@Param("id") Long id);

    List<ChatSession> listByUserId(@Param("userId") Long userId);

    int deleteById(@Param("id") Long id);
}
