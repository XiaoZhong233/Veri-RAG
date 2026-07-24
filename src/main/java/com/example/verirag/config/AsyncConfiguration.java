package com.example.verirag.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.TaskExecutor;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/** 后台摘要不占用 HTTP/SSE 请求线程。 */
@Configuration
@EnableAsync
public class AsyncConfiguration {

    @Bean("conversationSummaryExecutor")
    public TaskExecutor conversationSummaryExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(2);
        executor.setQueueCapacity(20);
        executor.setThreadNamePrefix("conversation-summary-");
        executor.initialize();
        return executor;
    }
}
