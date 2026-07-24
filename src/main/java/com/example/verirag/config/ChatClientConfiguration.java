package com.example.verirag.config;

import com.example.verirag.advisor.LoggerAdvisor;
import com.example.verirag.memory.MyBatisChatMemoryRepository;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.beans.factory.annotation.Qualifier;

@Configuration
public class ChatClientConfiguration {

    @Bean
    public ChatMemory chatMemory(MyBatisChatMemoryRepository repository) {
        return MessageWindowChatMemory.builder()
                .chatMemoryRepository(repository)
                // 一段摘要 SystemMessage + 最近两轮（4 条）原始消息。
                .maxMessages(5)
                .build();
    }

    @Bean
    public ChatClient chatClient(OpenAiChatModel model, ChatMemory chatMemory){
        return ChatClient
                .builder(model)
                .defaultAdvisors(
//                        MessageChatMemoryAdvisor.builder(chatMemory).build()
//                        loggerAdvisor
                )
                .build();
    }

    /**
     * 用于压缩历史的独立客户端，不挂会话记忆 Advisor，避免摘要任务读取或写入正常对话上下文。
     */
    @Bean("summaryChatClient")
    public ChatClient summaryChatClient(OpenAiChatModel model) {
        return ChatClient.builder(model).build();
    }
}
