package com.example.verirag.config;

import org.springframework.ai.embedding.BatchingStrategy;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.redis.RedisVectorStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.jedis.JedisConnectionFactory;
import redis.clients.jedis.DefaultJedisClientConfig;
import redis.clients.jedis.JedisClientConfig;
import redis.clients.jedis.RedisClient;

/**
 * Redis 向量库配置。
 */
@Configuration
public class RedisVectorStoreConfig {

    @Bean
    public RedisVectorStore redisVectorStore(EmbeddingModel embeddingModel,
                                             BatchingStrategy batchingStrategy,
                                             JedisConnectionFactory connectionFactory,
                                             @Value("${spring.ai.vectorstore.redis.index-name:veri-rag-index}") String indexName,
                                             @Value("${spring.ai.vectorstore.redis.prefix:veri-rag:embedding:}") String prefix,
                                             @Value("${spring.ai.vectorstore.redis.initialize-schema:true}") boolean initializeSchema) {
        JedisClientConfig clientConfig = DefaultJedisClientConfig.builder()
                .ssl(connectionFactory.isUseSsl())
                .clientName(connectionFactory.getClientName())
                .timeoutMillis(connectionFactory.getTimeout())
                .password(connectionFactory.getPassword())
                .build();
        RedisClient client = RedisClient.builder()
                .hostAndPort(connectionFactory.getHostName(), connectionFactory.getPort())
                .clientConfig(clientConfig)
                .build();

        return RedisVectorStore.builder(client, embeddingModel)
                .indexName(indexName)
                .prefix(prefix)
                .initializeSchema(initializeSchema)
                .batchingStrategy(batchingStrategy)
                .metadataFields(
                        RedisVectorStore.MetadataField.tag("docId"),
                        RedisVectorStore.MetadataField.tag("categoryId"),
                        RedisVectorStore.MetadataField.text("title"))
                .build();
    }
}
