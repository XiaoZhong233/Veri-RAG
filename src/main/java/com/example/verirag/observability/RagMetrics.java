package com.example.verirag.observability;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * RAG 业务指标集中定义。
 *
 * 标签只使用有限集合（outcome），避免把 sessionId、问题内容、文档标题等高基数数据写入 Prometheus。
 */
@Component
public class RagMetrics {

    private final MeterRegistry meterRegistry;

    public RagMetrics(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    public void recordRequest(Duration duration, String outcome) {
        Timer.builder("rag.request.duration")
                .description("End-to-end RAG request duration")
                .tag("outcome", outcome)
                .publishPercentileHistogram()
                .serviceLevelObjectives(Duration.ofSeconds(1), Duration.ofSeconds(3),
                        Duration.ofSeconds(5), Duration.ofSeconds(10))
                .register(meterRegistry)
                .record(duration);
    }

    public void recordCache(boolean hit) {
        Counter.builder("rag.cache.requests")
                .description("RAG answer cache lookups")
                .tag("result", hit ? "hit" : "miss")
                .register(meterRegistry)
                .increment();
    }

    public void recordRetrieval(Duration duration, int chunkCount, String outcome) {
        Timer.builder("rag.retrieval.duration")
                .description("Embedding and vector retrieval duration")
                .tag("outcome", outcome)
                .publishPercentileHistogram()
                .register(meterRegistry)
                .record(duration);
        DistributionSummary.builder("rag.retrieval.chunks")
                .description("Number of relevant chunks returned by retrieval")
                .register(meterRegistry)
                .record(Math.max(chunkCount, 0));
    }

    /** 记录流式响应的首个 Token 到达耗时（TTFT）。 */
    public void recordLlmFirstToken(Duration duration) {
        Timer.builder("rag.llm.first-token.duration")
                .description("Time to first streamed LLM token")
                .publishPercentileHistogram()
                .register(meterRegistry)
                .record(duration);
    }

    public void recordLlm(Duration duration, String mode, String outcome) {
        Timer.builder("rag.llm.duration")
                .description("LLM generation duration")
                .tag("mode", mode)
                .tag("outcome", outcome)
                .publishPercentileHistogram()
                .register(meterRegistry)
                .record(duration);
    }
}
