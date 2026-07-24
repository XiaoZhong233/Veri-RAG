package com.example.verirag.prompt;

import com.example.verirag.dto.SalesRecommendationSaveRequest;
import com.example.verirag.dto.SalesRecommendationView;
import com.example.verirag.service.SalesRecommendationService;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;

class SalesRecommendationPromptManagerTests {

    @Test
    void rendersEnabledRecommendationsAsTieBreakersOnly() {
        SalesRecommendationPromptManager manager = new SalesRecommendationPromptManager(
                new RecommendationFixtureService());
        ReflectionTestUtils.setField(manager, "promptResource",
                new ClassPathResource("prompts/sales-recommendation-prompt.txt"));
        ReflectionTestUtils.invokeMethod(manager, "load");

        assertThat(manager.systemPrompt())
                .contains("Drapery Place Residence")
                .contains("同等条件排序")
                .contains("不能覆盖核心房源规则")
                .contains("优先级 10：Drapery Place Residence（公寓编码：drapery-place）")
                .contains("必须先独立完成地点、日期、租期、预算、房型和库存等硬条件筛选")
                .contains("不得为了销售偏好")
                .contains("不得放宽用户条件")
                .contains("不得为它重复调用 Tool")
                .contains("不在 Tool 返回结果中时不得补入或提及")
                .contains("不要暴露“销售排序”")
                .doesNotContain("{{preferred_residences}}")
                .doesNotContain("内部主推原因");
    }

    @Test
    void returnsNoSalesPromptWhenNoRecommendationIsEnabled() {
        SalesRecommendationPromptManager manager = new SalesRecommendationPromptManager(
                new EmptyRecommendationService());
        ReflectionTestUtils.setField(manager, "promptResource",
                new ClassPathResource("prompts/sales-recommendation-prompt.txt"));
        ReflectionTestUtils.invokeMethod(manager, "load");

        assertThat(manager.systemPrompt()).isEmpty();
    }

    private static class RecommendationFixtureService extends EmptyRecommendationService {
        @Override
        public java.util.List<SalesRecommendationView> enabledRecommendations() {
            return java.util.List.of(new SalesRecommendationView(
                    1L, 7L, "drapery-place", "Drapery Place Residence",
                    "London", 10, 1, "内部主推原因", null, null));
        }
    }

    private static class EmptyRecommendationService implements SalesRecommendationService {
        @Override
        public java.util.List<SalesRecommendationView> list() {
            return java.util.List.of();
        }

        @Override
        public SalesRecommendationView get(Long id) {
            return null;
        }

        @Override
        public void save(SalesRecommendationSaveRequest request) {
        }

        @Override
        public void delete(Long id) {
        }

        @Override
        public java.util.List<SalesRecommendationView> enabledRecommendations() {
            return java.util.List.of();
        }
    }
}
