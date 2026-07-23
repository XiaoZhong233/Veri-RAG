package com.example.verirag.config;

import com.example.verirag.advisor.LoggerAdvisor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.client.advisor.SimpleLoggerAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.InMemoryChatMemoryRepository;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ChatClientConfiguration {

//    @Bean
//    public ChatMemory chatMemory(){
//        return MessageWindowChatMemory.builder()
//                .chatMemoryRepository(new InMemoryChatMemoryRepository())
//                .maxMessages(10).build();
//    }

    @Bean
    public ChatClient chatClient(OpenAiChatModel model){
        SimpleLoggerAdvisor logAdvisor = new SimpleLoggerAdvisor();
        return ChatClient
                .builder(model)
                .defaultAdvisors(
                        logAdvisor
                )
                .build();
    }
}
