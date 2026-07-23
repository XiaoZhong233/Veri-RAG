package com.example.verirag.config;

import com.example.verirag.advisor.LoggerAdvisor;
import com.example.verirag.memory.MyBatisChatMemoryRepository;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.client.advisor.SimpleLoggerAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

@Configuration
public class ChatClientConfiguration {

    @Bean
    public ChatMemory chatMemory(MyBatisChatMemoryRepository repository) {
        return MessageWindowChatMemory.builder()
                .chatMemoryRepository(repository)
                .maxMessages(6)
                .build();
    }

    @Bean
    public ChatClient chatClient(OpenAiChatModel model, LoggerAdvisor loggerAdvisor, ChatMemory chatMemory){
        return ChatClient
                .builder(model)
                .defaultAdvisors(
                        MessageChatMemoryAdvisor.builder(chatMemory).build(),
                        new SimpleLoggerAdvisor(Ordered.LOWEST_PRECEDENCE - 1),
                        loggerAdvisor
                )
                .build();
    }
}
