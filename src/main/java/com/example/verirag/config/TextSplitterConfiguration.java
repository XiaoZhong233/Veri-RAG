package com.example.verirag.config;

import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 文本分块配置类
 */
@Configuration
public class TextSplitterConfiguration {


    @Bean
    public TokenTextSplitter tokenTextSplitter(
            @Value("${rag.ingest.chunk-size:800}") int chunkSize,
            @Value("${rag.ingest.max-num-chunks:10000}") int maxNumChunks) {
        return TokenTextSplitter.builder()
                .withChunkSize(chunkSize)
                .withMaxNumChunks(maxNumChunks)
                .build();
    }
}
