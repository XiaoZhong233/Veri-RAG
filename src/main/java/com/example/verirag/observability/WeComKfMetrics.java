package com.example.verirag.observability;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.time.Duration;

/** 微信客服链路指标；标签只使用有限状态，避免客户 ID 等高基数数据进入 Prometheus。 */
@Component
public class WeComKfMetrics {

    private final MeterRegistry meterRegistry;

    public WeComKfMetrics(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    public void recordSync(Duration duration, String outcome) {
        timer("wecom.kf.sync.duration", "Sync callback messages", outcome).record(duration);
    }

    public void recordQueue(Duration duration) {
        Timer.builder("wecom.kf.queue.wait.duration")
                .description("Time a customer message waits before processing")
                .publishPercentileHistogram()
                .register(meterRegistry)
                .record(duration);
    }

    public void recordReply(Duration duration, String outcome) {
        timer("wecom.kf.reply.duration", "Customer reply processing", outcome).record(duration);
    }

    public void increment(String event) {
        Counter.builder("wecom.kf.messages")
                .description("WeCom KF message events")
                .tag("event", event)
                .register(meterRegistry)
                .increment();
    }

    private Timer timer(String name, String description, String outcome) {
        return Timer.builder(name)
                .description(description)
                .tag("outcome", outcome)
                .publishPercentileHistogram()
                .register(meterRegistry);
    }
}
