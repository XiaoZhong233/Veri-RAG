package com.example.verirag.prompt;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;

class RagPromptManagerTests {

    @Test
    void loadsAccommodationAvailabilityPrompts() {
        RagPromptManager manager = new RagPromptManager();
        ReflectionTestUtils.setField(manager, "systemPromptResource",
                new ClassPathResource("prompts/rag-system-prompt.txt"));
        ReflectionTestUtils.setField(manager, "noContextResource",
                new ClassPathResource("prompts/rag-no-context.txt"));
        ReflectionTestUtils.setField(manager, "contextPrefixResource",
                new ClassPathResource("prompts/rag-context-prefix.txt"));
        ReflectionTestUtils.setField(manager, "contextItemResource",
                new ClassPathResource("prompts/rag-context-item.txt"));

        ReflectionTestUtils.invokeMethod(manager, "load");

        assertThat(manager.systemPrompt())
                .contains("英国学生公寓房源查询助手", "地点", "入住时间", "可预订")
                .contains("不得将不同公寓或不同房型片段中的价格")
                .contains("绝对不能把本次命中的片段数量当作公寓总数")
                .contains("具体起租日待确认", "6个月”统一按约 26 周理解")
                .contains("至少 4 个不同公寓")
                .contains("不是 4 条房型结果")
                .contains("不得将其描述为可预订")
                .contains("限时优惠已过期");
        assertThat(manager.noContext()).contains("不要编造公寓、房型、日期、价格或库存");
        assertThat(manager.contextPrefix())
                .contains("片段可能对应独立房型、公寓位置资料")
                .contains("候选片段数量不是公寓总数");
        assertThat(manager.contextItem(1, "价表", "## 公寓"))
                .contains("候选房型 1", "来源：价表", "## 公寓");
    }
}
