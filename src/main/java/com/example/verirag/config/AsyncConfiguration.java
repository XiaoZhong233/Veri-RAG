package com.example.verirag.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.TaskExecutor;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

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

    /** 微信客服回调快速确认，消息同步和模型问答在独立线程池执行。 */
    @Bean("wecomKfExecutor")
    public TaskExecutor wecomKfExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        // 客服请求按客户串行、不同客户并行。核心线程数不能设为 1，否则队列未满前
        // maxPoolSize 不会生效，所有客户仍会排队。
        executor.setCorePoolSize(4);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(200);
        executor.setThreadNamePrefix("wecom-kf-");
        executor.initialize();
        return executor;
    }

    /** 延迟发送微信客服“正在检索”提示，不占用消息同步线程。 */
    @Bean("wecomKfProgressScheduler")
    public ThreadPoolTaskScheduler wecomKfProgressScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        // 调度任务会发送一次网络请求；至少允许少量并发，避免单个网络抖动拖住全部提示。
        scheduler.setPoolSize(2);
        scheduler.setThreadNamePrefix("wecom-kf-progress-");
        scheduler.setRemoveOnCancelPolicy(true);
        scheduler.initialize();
        return scheduler;
    }
}
