package com.example.verirag.prompt;

import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;
import org.springframework.util.StreamUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/** 统一加载 RAG Prompt 模板，避免业务代码散落多行提示词。 */
@Component
public class RagPromptManager {

    @Value("classpath:prompts/rag-system-prompt.txt")
    private Resource systemPromptResource;
    @Value("classpath:prompts/rag-no-context.txt")
    private Resource noContextResource;
    @Value("classpath:prompts/rag-context-prefix.txt")
    private Resource contextPrefixResource;
    @Value("classpath:prompts/rag-context-item.txt")
    private Resource contextItemResource;

    private String systemPrompt;
    private String noContext;
    private String contextPrefix;
    private String contextItem;

    @PostConstruct
    void load() {
        systemPrompt = read(systemPromptResource);
        noContext = read(noContextResource);
        contextPrefix = read(contextPrefixResource);
        contextItem = read(contextItemResource);
    }

    public String systemPrompt() { return systemPrompt; }
    public String noContext() { return noContext; }
    public String contextPrefix() { return contextPrefix; }
    public String contextItem(int number, String title, String text) {
        return contextItem.formatted(number, title, text);
    }

    private static String read(Resource resource) {
        try {
            return StreamUtils.copyToString(resource.getInputStream(), StandardCharsets.UTF_8).strip();
        }
        catch (IOException ex) {
            throw new IllegalStateException("Failed to load prompt resource: " + resource, ex);
        }
    }
}
