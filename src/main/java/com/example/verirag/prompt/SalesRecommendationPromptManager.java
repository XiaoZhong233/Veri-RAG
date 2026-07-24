package com.example.verirag.prompt;

import com.example.verirag.dto.SalesRecommendationView;
import com.example.verirag.service.SalesRecommendationService;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;
import org.springframework.util.StreamUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

@Component
@RequiredArgsConstructor
public class SalesRecommendationPromptManager {

    private static final String RECOMMENDATION_PLACEHOLDER = "{{preferred_residences}}";

    private final SalesRecommendationService salesRecommendationService;

    @Value("classpath:prompts/sales-recommendation-prompt.txt")
    private Resource promptResource;

    private String systemPrompt;

    @PostConstruct
    void load() {
        try {
            systemPrompt = StreamUtils.copyToString(
                    promptResource.getInputStream(), StandardCharsets.UTF_8).strip();
        }
        catch (IOException ex) {
            throw new IllegalStateException("Failed to load sales recommendation prompt", ex);
        }
    }

    public String systemPrompt() {
        return render(salesRecommendationService.enabledRecommendations());
    }

    String render(java.util.List<SalesRecommendationView> recommendations) {
        if (recommendations == null || recommendations.isEmpty()) {
            return "";
        }
        String preferredResidences = recommendations.stream()
                .map(item -> "- 优先级 " + item.priority()
                        + "：" + item.residenceName()
                        + "（公寓编码：" + item.residenceSourceId() + "）")
                .collect(java.util.stream.Collectors.joining("\n"));
        return systemPrompt.replace(RECOMMENDATION_PLACEHOLDER, preferredResidences);
    }
}
