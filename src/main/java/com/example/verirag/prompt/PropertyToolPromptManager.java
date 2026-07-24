package com.example.verirag.prompt;

import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;
import org.springframework.util.StreamUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

@Component
public class PropertyToolPromptManager {

    @Value("classpath:prompts/property-tool-system-prompt.txt")
    private Resource promptResource;

    private String systemPrompt;

    @PostConstruct
    void load() {
        try {
            systemPrompt = StreamUtils.copyToString(
                    promptResource.getInputStream(), StandardCharsets.UTF_8).strip();
        }
        catch (IOException ex) {
            throw new IllegalStateException("Failed to load property tool prompt", ex);
        }
    }

    public String systemPrompt() {
        return systemPrompt;
    }
}
