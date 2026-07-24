package com.example.verirag.tool;

import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 每次只向模型暴露当前意图对应的一个 Tool，避免模型先查询名单、
 * 再查询库存等探索式多轮调用。
 */
@Component
public class PropertyToolSelector {

    private final Map<String, ToolCallback> callbacksByName;

    public PropertyToolSelector(PropertyQueryTools propertyQueryTools) {
        callbacksByName = Arrays.stream(ToolCallbacks.from(propertyQueryTools))
                .collect(Collectors.toUnmodifiableMap(
                        callback -> callback.getToolDefinition().name(),
                        Function.identity()));
    }

    public ToolCallback[] callbacksFor(PropertyQueryIntent intent) {
        if (intent == null || !intent.structured()) {
            return new ToolCallback[0];
        }
        ToolCallback callback = callbacksByName.get(intent.toolName());
        if (callback == null) {
            throw new IllegalStateException(
                    "Property Tool is not registered: " + intent.toolName());
        }
        return new ToolCallback[]{callback};
    }
}
