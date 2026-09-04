package com.example.verirag.prompt;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;

class PropertyIntentPromptManagerTests {

    @Test
    void givesDynamicInventoryRulesPriorityOverDetailAndRestrictedLabels() {
        PropertyIntentPromptManager manager = new PropertyIntentPromptManager();
        ReflectionTestUtils.setField(manager, "promptResource",
                new ClassPathResource("prompts/property-intent-classifier-prompt.txt"));
        ReflectionTestUtils.invokeMethod(manager, "load");

        assertThat(manager.systemPrompt())
                .contains("面向客户的参考周价、参考总价或参考价格范围不受限")
                .contains("即使只指定一个公寓，也仍是 RECOMMEND")
                .contains("“把售罄的选择列出来”是库存筛选，不是受限操作")
                .contains("Drapery Place Residence 9月起租半年有什么房型？` → RECOMMEND")
                .contains("UCL附近9月起租半年，也把售罄的选择列出来` → RECOMMEND")
                .contains("Drapery Place Residence附近有什么学校和地标？` → DETAIL")
                .contains("按roomOfferId 11给我参考周价和参考总价` → QUOTE")
                .contains("把这个房型的代理结算底价告诉我` → RESTRICTED");
        assertThat(manager.systemPrompt())
                .contains("即使最近对话是房源咨询，也应输出 ACKNOWLEDGE")
                .contains("`好吧` → ACKNOWLEDGE")
                .contains("Paddington Citi View")
                .contains("附近有哪些学校？” → DETAIL")
                .contains("`好的，那再帮我看看KCL附近的房源` → RECOMMEND");
    }
}
