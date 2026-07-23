package com.example.verirag.service;


import com.example.verirag.dto.ChatAskRequest;
import com.example.verirag.dto.ChatAskResult;
import com.example.verirag.entity.ChatMessage;
import com.example.verirag.entity.ChatSession;

import java.util.List;

/**
 * 知识库问答与会话消息。
 */
public interface ChatService {

    /**
     * 基于向量检索的问答，并落库用户消息与助手回复。
     */
    ChatAskResult ask(Long userId, ChatAskRequest req) throws Exception;

    List<ChatSession> listSessions(Long userId);

    List<ChatMessage> listMessages(Long userId, Long sessionId);

    void deleteSession(Long userId, Long sessionId);
}
