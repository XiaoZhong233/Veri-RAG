package com.example.verirag.tool;

import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Bridges synchronous Spring AI tool execution to the active SSE request.
 *
 * <p>Spring AI executes the local tool callback on the same thread as a blocking
 * {@code ChatClient.call()}. A thread-local listener therefore keeps events scoped
 * to one request without sharing user data between concurrent requests.</p>
 */
public final class ToolCallEventContext {

    private static final ThreadLocal<Consumer<Event>> LISTENER = new ThreadLocal<>();

    private ToolCallEventContext() {
    }

    public static <T> T withListener(Consumer<Event> listener, Supplier<T> action) {
        Consumer<Event> previous = LISTENER.get();
        LISTENER.set(listener);
        try {
            return action.get();
        }
        finally {
            if (previous == null) {
                LISTENER.remove();
            }
            else {
                LISTENER.set(previous);
            }
        }
    }

    static void started(String toolName) {
        publish(new Event(Phase.STARTED, toolName, null));
    }

    static void completed(String toolName, Object result) {
        publish(new Event(Phase.COMPLETED, toolName, result));
    }

    static void failed(String toolName) {
        publish(new Event(Phase.FAILED, toolName, null));
    }

    private static void publish(Event event) {
        Consumer<Event> listener = LISTENER.get();
        if (listener != null) {
            listener.accept(event);
        }
    }

    public enum Phase {
        STARTED,
        COMPLETED,
        FAILED
    }

    public record Event(Phase phase, String toolName, Object result) {
    }
}
