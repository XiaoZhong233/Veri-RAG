package com.example.verirag.config;

import com.example.verirag.advisor.LoggerAdvisor;
import org.springframework.ai.chat.client.ChatClient;
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
    public ChatClient chatClient(OpenAiChatModel model, LoggerAdvisor loggerAdvisor){
        return ChatClient
                .builder(model)
                .defaultAdvisors(
                        loggerAdvisor
                )
                .build();
    }
}
