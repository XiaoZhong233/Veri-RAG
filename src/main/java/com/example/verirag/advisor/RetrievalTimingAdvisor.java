package com.example.verirag.advisor;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.function.Supplier;

/** 向量检索耗时记录器；检索发生在 ChatClient 调用链之外，因此独立包装。 */
@Slf4j
@Component
public class RetrievalTimingAdvisor {

    public <T> T advise(Long sessionId, Supplier<T> retrieval) {
        long start = System.nanoTime();
        try {
            T result = retrieval.get();
            int count = result instanceof List<?> list ? list.size() : -1;
            log.info("RAG retrieval completed: sessionId={}, chunks={}, duration={}ms",
                    sessionId, count, elapsedMillis(start));
            return result;
        }
        catch (RuntimeException ex) {
            log.warn("RAG retrieval failed: sessionId={}, duration={}ms, error={}",
                    sessionId, elapsedMillis(start), ex.toString());
            throw ex;
        }
    }

    private static long elapsedMillis(long start) {
        return (System.nanoTime() - start) / 1_000_000;
    }
}
