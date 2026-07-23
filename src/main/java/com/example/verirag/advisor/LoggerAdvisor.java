package com.example.verirag.advisor;

import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisor;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisorChain;
import org.springframework.stereotype.Component;
import org.springframework.core.Ordered;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

@Component
public class LoggerAdvisor implements CallAdvisor, StreamAdvisor {

    private static final Logger log = LoggerFactory.getLogger(LoggerAdvisor.class);

    @Override
    public ChatClientResponse adviseCall(ChatClientRequest request, CallAdvisorChain chain) {
        long start = System.nanoTime();
        log.debug("AI call started: {}", promptSummary(request));
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
            AtomicBoolean firstChunk = new AtomicBoolean(true);
            log.debug("AI stream started: {}", promptSummary(request));
            return chain.nextStream(request)
                    .doOnNext(response -> {
                        if (firstChunk.compareAndSet(true, false)) {
                            log.info("AI stream first chunk in {} ms", elapsedMillis(start));
                        }
                    })
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
        return Ordered.LOWEST_PRECEDENCE;
    }

    private long elapsedMillis(long start) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
    }

    private static String promptSummary(ChatClientRequest request) {
        String roles = request.prompt().getInstructions().stream()
                .map(message -> message.getMessageType().name())
                .collect(Collectors.joining(","));
        return "messageCount=" + request.prompt().getInstructions().size() + ", roles=" + roles;
    }
}
