package com.example.verirag.advisor;

import com.example.verirag.observability.RagMetrics;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.function.Supplier;

/** 向量检索耗时记录器；检索发生在 ChatClient 调用链之外，因此独立包装。 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RetrievalTimingAdvisor {

    private final RagMetrics ragMetrics;
    private final ObservationRegistry observationRegistry;

    public <T> T advise(Long sessionId, Supplier<T> retrieval) {
        long start = System.nanoTime();
        Observation observation = Observation.createNotStarted("rag.retrieval", observationRegistry)
                .lowCardinalityKeyValue("rag.stage", "retrieval")
                .start();
        try {
            try (Observation.Scope ignored = observation.openScope()) {
                T result = retrieval.get();
                int count = result instanceof List<?> list ? list.size() : 0;
                long elapsed = elapsedMillis(start);
                ragMetrics.recordRetrieval(java.time.Duration.ofMillis(elapsed), count,
                        count > 0 ? "hit" : "empty");
                log.info("event=rag.retrieval.completed sessionId={} chunks={} durationMs={}",
                        sessionId, count, elapsed);
                return result;
            }
        }
        catch (RuntimeException ex) {
            observation.error(ex);
            long elapsed = elapsedMillis(start);
            ragMetrics.recordRetrieval(java.time.Duration.ofMillis(elapsed), 0, "error");
            log.warn("event=rag.retrieval.failed sessionId={} durationMs={} error={}",
                    sessionId, elapsed, ex.toString());
            throw ex;
        }
        finally {
            observation.stop();
        }
    }

    private static long elapsedMillis(long start) {
        return (System.nanoTime() - start) / 1_000_000;
    }
}
