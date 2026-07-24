package com.example.verirag.tool;

import com.example.verirag.entity.ChatMessage;
import com.example.verirag.prompt.PropertyIntentPromptManager;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 确定性规则无法判断、但问题具有房源领域信号时，使用一次轻量模型调用补充分流。
 */
@Component
@Slf4j
public class PropertyIntentClassifier {

    private static final Pattern INTENT_TOKEN = Pattern.compile(
            "\\b(NONE|RECOMMEND|QUOTE|DETAIL|LIST|SUMMARY)\\b",
            Pattern.CASE_INSENSITIVE);
    private static final int MAX_LOG_QUESTION_CHARS = 300;
    private static final int MAX_HISTORY_MESSAGES = 4;
    private static final int MAX_HISTORY_CHARS = 1600;
    private final ChatClient classifierChatClient;
    private final PropertyIntentPromptManager promptManager;

    @Value("${rag.intent-classifier.enabled:true}")
    private boolean enabled;

    @Value("${rag.intent-classifier.timeout:15s}")
    private Duration timeout;

    @Value("${rag.intent-classifier.model:}")
    private String model;

    @Value("${rag.intent-classifier.thinking-enabled:false}")
    private boolean thinkingEnabled;

    public PropertyIntentClassifier(
            @Qualifier("manualHistoryChatClient") ChatClient classifierChatClient,
            PropertyIntentPromptManager promptManager) {
        this.classifierChatClient = classifierChatClient;
        this.promptManager = promptManager;
    }

    public PropertyQueryIntent resolve(String question, List<ChatMessage> history) {
        PropertyQueryIntent ruleIntent = PropertyQueryRouter.route(question, history);
        if (ruleIntent.structured()) {
            logResolution(question, "JAVA_RULE", ruleIntent, null);
            return ruleIntent;
        }
        if (!enabled) {
            logResolution(question, "JAVA_RULE", PropertyQueryIntent.NONE,
                    "classifier_disabled");
            return PropertyQueryIntent.NONE;
        }
        if (!PropertyQueryRouter.needsModelClassification(question, history)) {
            logResolution(question, "JAVA_RULE", PropertyQueryIntent.NONE,
                    "no_property_signal");
            return PropertyQueryIntent.NONE;
        }

        long started = System.nanoTime();
        try {
            var options = OpenAiChatOptions.builder()
                    .temperature(0.0)
                    .maxTokens(16)
                    .timeout(timeout);
            if (model != null && !model.isBlank()) {
                options.model(model.strip());
            }
            if (!thinkingEnabled) {
                options.extraBody(Map.of("enable_thinking", false));
            }
            String content = classifierChatClient.prompt()
                    .system(promptManager.systemPrompt())
                    .user(buildInput(question, history))
                    .options(options)
                    .call()
                    .content();
            PropertyQueryIntent classified = parseIntent(content);
            long durationMs = (System.nanoTime() - started) / 1_000_000L;
            logResolution(question, "MODEL_CLASSIFIER", classified,
                    classified.structured() ? "durationMs=" + durationMs
                            : "model_returned_none,durationMs=" + durationMs);
            return classified;
        }
        catch (RuntimeException ex) {
            long durationMs = (System.nanoTime() - started) / 1_000_000L;
            log.warn("event=property.intent.resolved question=\"{}\" source=MODEL_FALLBACK "
                            + "intent=NONE route=RAG reason=classifier_error durationMs={} error={}",
                    logQuestion(question), durationMs, ex.toString());
            return PropertyQueryIntent.NONE;
        }
    }

    private static void logResolution(String question, String source,
                                      PropertyQueryIntent intent, String reason) {
        String route = intent.structured() ? "TOOL" : "RAG";
        String tool = intent.structured() ? intent.toolName() : "-";
        if (reason == null || reason.isBlank()) {
            log.info("event=property.intent.resolved question=\"{}\" source={} intent={} "
                            + "route={} tool={}",
                    logQuestion(question), source, intent, route, tool);
            return;
        }
        log.info("event=property.intent.resolved question=\"{}\" source={} intent={} "
                        + "route={} tool={} reason={}",
                logQuestion(question), source, intent, route, tool, reason);
    }

    private static String logQuestion(String question) {
        String value = Objects.toString(question, "")
                .replaceAll("[\\r\\n\\t]+", " ")
                .replaceAll("\\s{2,}", " ")
                .strip();
        if (value.length() <= MAX_LOG_QUESTION_CHARS) {
            return value;
        }
        return value.substring(0, MAX_LOG_QUESTION_CHARS) + "…";
    }

    static PropertyQueryIntent parseIntent(String content) {
        Matcher matcher = INTENT_TOKEN.matcher(Objects.toString(content, ""));
        Set<PropertyQueryIntent> matched = new LinkedHashSet<>();
        while (matcher.find()) {
            matched.add(PropertyQueryIntent.valueOf(
                    matcher.group(1).toUpperCase(Locale.ROOT)));
        }
        return matched.size() == 1
                ? matched.iterator().next()
                : PropertyQueryIntent.NONE;
    }

    private static String buildInput(String question, List<ChatMessage> history) {
        StringBuilder input = new StringBuilder();
        if (history != null && !history.isEmpty()) {
            input.append("最近对话：\n");
            int start = Math.max(0, history.size() - MAX_HISTORY_MESSAGES);
            for (int i = start; i < history.size(); i++) {
                ChatMessage message = history.get(i);
                String content = Objects.toString(message.getContent(), "").strip();
                if (content.isBlank()) {
                    continue;
                }
                input.append("ASSISTANT".equals(message.getRole()) ? "助手：" : "用户：")
                        .append(content)
                        .append('\n');
                if (input.length() >= MAX_HISTORY_CHARS) {
                    input.setLength(MAX_HISTORY_CHARS);
                    break;
                }
            }
        }
        input.append("当前用户请求：\n")
                .append(Objects.toString(question, "").strip());
        return input.toString();
    }
}
