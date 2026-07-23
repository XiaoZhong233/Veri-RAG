package com.example.verirag.config;

import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 文本分块配置类
 */
@Configuration
public class TextSplitterConfiguration {


    @Bean
    public TokenTextSplitter tokenTextSplitter(){
        return TokenTextSplitter.builder()
                .withChunkSize(800)
                .withMaxNumChunks(10000)
                .build();
    }
}
