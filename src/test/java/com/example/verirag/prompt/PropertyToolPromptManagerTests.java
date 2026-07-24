package com.example.verirag.prompt;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;

class PropertyToolPromptManagerTests {

    @Test
    void keepsStructuredPropertyRulesInToolPrompt() {
        PropertyToolPromptManager manager = new PropertyToolPromptManager();
        ReflectionTestUtils.setField(manager, "promptResource",
                new ClassPathResource("prompts/property-tool-system-prompt.txt"));
        ReflectionTestUtils.invokeMethod(manager, "load");

        assertThat(manager.systemPrompt())
                .contains("系统只会提供一个与本次意图对应的 Tool")
                .contains("均来自提供的 Tool")
                .contains("不得再从知识库")
                .contains("历史报价、房情")
                .contains("search_room_offers")
                .contains("get_residence_details")
                .contains("不要讨论、请求或尝试调用未提供的 Tool")
                .contains("quote_room_offer")
                .contains("最多展示4个不同公寓")
                .contains("每个公寓最多2个房型")
                .contains("所有公寓合计最多6个房型选项")
                .contains("严禁输出公寓名和位置为空的续行")
                .contains("limitResidences 必须传4")
                .contains("这是上限而不是必须凑满的数量")
                .contains("每次找房最多调用一次 search_room_offers")
                .contains("不得拆成多批")
                .contains("nearbyPlaceKeyword")
                .contains("maxTravelMinutes 传25")
                .contains("本次结构化查询返回")
                .contains("只有用户已经指定一个 roomOfferId")
                .contains("不得把 UCL East 房源混入结果")
                .contains("最终表格最多4行公寓数据")
                .contains("一句话结论")
                .contains("未查询售罄时不得说“没有售罄房源”")
                .contains("| 公寓 | 位置参考 | 推荐房型 | 可入住时间 | 租期 | 每周价格 | 预计总价 | 库存 | 匹配说明 |")
                .contains("表格一行代表一个不同公寓")
                .contains("只有 Tool 返回 estimatedTotalPrice")
                .contains("普通学校或地标找房时应留空")
                .contains("不得声称精确公里数")
                .contains("UCL Bloomsbury 主校区")
                .contains("未经过地图实时计算")
                .doesNotContain("Drapery Place Residence");
    }
}
