package com.example.verirag.advisor;

import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisor;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisorChain;
import org.springframework.stereotype.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;

import java.util.concurrent.TimeUnit;

@Component
public class LoggerAdvisor implements CallAdvisor, StreamAdvisor {

    private static final Logger log = LoggerFactory.getLogger(LoggerAdvisor.class);

    @Override
    public ChatClientResponse adviseCall(ChatClientRequest request, CallAdvisorChain chain) {
        long start = System.nanoTime();
        log.debug("AI call started, request: {}", request);
        try {
            ChatClientResponse response = chain.nextCall(request);
            log.info("AI call completed in {} ms", elapsedMillis(start));
            return response;
        }
        catch (RuntimeException exception) {
            log.error("AI call failed after {} ms", elapsedMillis(start), exception);
            throw exception;
        }
    }

    @Override
    public Flux<ChatClientResponse> adviseStream(ChatClientRequest request, StreamAdvisorChain chain) {
        return Flux.defer(() -> {
            long start = System.nanoTime();
            log.debug("AI stream started");
            return chain.nextStream(request)
                    .doOnComplete(() -> log.info("AI stream completed in {} ms", elapsedMillis(start)))
                    .doOnError(exception -> log.error(
                            "AI stream failed after {} ms", elapsedMillis(start), exception));
        });
    }

    @Override
    public String getName() {
        return "simple-log";
    }

    @Override
    public int getOrder() {
        return 0;
    }

    private long elapsedMillis(long start) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
    }
}
