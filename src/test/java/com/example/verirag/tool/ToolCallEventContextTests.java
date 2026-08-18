package com.example.verirag.tool;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ToolCallEventContextTests {

    @Test
    void publishesOnlyInsideBoundListenerAndRestoresNestedListener() {
        List<String> outer = new ArrayList<>();
        List<String> inner = new ArrayList<>();

        ToolCallEventContext.withListener(
                event -> outer.add(event.phase() + ":" + event.toolName()),
                () -> {
                    ToolCallEventContext.started("outer_one");
                    ToolCallEventContext.withListener(
                            event -> inner.add(event.phase() + ":" + event.toolName()),
                            () -> {
                                ToolCallEventContext.completed("inner", "result");
                                return null;
                            });
                    ToolCallEventContext.failed("outer_two");
                    return null;
                });
        ToolCallEventContext.started("not_published");

        assertThat(outer).containsExactly(
                "STARTED:outer_one", "FAILED:outer_two");
        assertThat(inner).containsExactly("COMPLETED:inner");
    }

    @Test
    void includesCompletedToolResultForTimeoutFallback() {
        List<Object> results = new ArrayList<>();

        ToolCallEventContext.withListener(
                event -> results.add(event.result()),
                () -> {
                    ToolCallEventContext.completed("search_room_offers", "safe-result");
                    return null;
                });

        assertThat(results).containsExactly("safe-result");
    }
}
