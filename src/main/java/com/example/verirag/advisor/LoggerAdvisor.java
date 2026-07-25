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
import java.util.concurrent.atomic.AtomicInteger;

@Component
public class LoggerAdvisor implements CallAdvisor, StreamAdvisor {

    private static final Logger log = LoggerFactory.getLogger(LoggerAdvisor.class);

    @Override
    public ChatClientResponse adviseCall(ChatClientRequest request, CallAdvisorChain chain) {
        long start = System.nanoTime();
        log.info("event=rag.llm.request mode=sync");
        try {
            ChatClientResponse response = chain.nextCall(request);
            log.info("event=rag.llm.response mode=sync characters={}", responseText(response).length());
            long elapsed = elapsedMillis(start);
            log.info("event=rag.llm.completed mode=sync durationMs={}", elapsed);
            return response;
        }
        catch (RuntimeException exception) {
            long elapsed = elapsedMillis(start);
            log.error("event=rag.llm.failed mode=sync durationMs={}", elapsed, exception);
            throw exception;
        }
    }

    @Override
    public Flux<ChatClientResponse> adviseStream(ChatClientRequest request, StreamAdvisorChain chain) {
        return Flux.defer(() -> {
            long start = System.nanoTime();
            AtomicBoolean firstChunk = new AtomicBoolean(true);
            AtomicInteger outputCharacters = new AtomicInteger();
            log.info("event=rag.llm.request mode=stream");
            return chain.nextStream(request)
                    .doOnNext(response -> {
                        outputCharacters.addAndGet(responseText(response).length());
                        if (firstChunk.compareAndSet(true, false)) {
                            long elapsed = elapsedMillis(start);
                            log.info("event=rag.llm.first_token durationMs={}", elapsed);
                        }
                    })
                    .doOnComplete(() -> {
                        log.info("event=rag.llm.response mode=stream characters={}", outputCharacters.get());
                        long elapsed = elapsedMillis(start);
                        log.info("event=rag.llm.completed mode=stream durationMs={}", elapsed);
                    })
                    .doOnError(exception -> {
                        long elapsed = elapsedMillis(start);
                        log.error("event=rag.llm.failed mode=stream durationMs={}", elapsed, exception);
                    });
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

    private static String responseText(ChatClientResponse response) {
        if (response == null || response.chatResponse() == null || response.chatResponse().getResults() == null) {
            return "";
        }
        return response.chatResponse().getResults().stream()
                .map(result -> result.getOutput() == null ? "" : result.getOutput().getText())
                .filter(text -> text != null && !text.isEmpty())
                .collect(java.util.stream.Collectors.joining());
    }
}
