package com.example.verirag.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * 企业微信机器人/微信客服会话与本地 RAG 会话的持久化映射。
 */
@Mapper
public interface WeComConversationMapper {

    /**
     * 查询企业微信会话上一次使用的本地聊天会话。
     * 首次对话返回 null，由 ChatService 创建新的 session。
     */
    Long selectSessionId(@Param("botId") String botId,
                         @Param("conversationKey") String conversationKey,
                         @Param("userId") Long userId);

    /**
     * 保存或更新会话映射。ChatService 发出 meta 事件后即可获得新 sessionId。
     */
    int upsertSessionId(@Param("botId") String botId,
                        @Param("conversationKey") String conversationKey,
                        @Param("userId") Long userId,
                        @Param("sessionId") Long sessionId);
}
