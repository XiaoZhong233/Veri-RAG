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
                .contains("意图澄清、条件补充和受限请求不会提供 Tool")
                .contains("必须使用用户当前消息的主要语言完整回答")
                .contains("英文问题用英文")
                .contains("均来自提供的 Tool")
                .contains("不得再从知识库")
                .contains("历史报价、房情")
                .contains("search_room_offers")
                .contains("get_residence_details")
                .contains("不要讨论、请求或尝试调用未提供的 Tool")
                .contains("check_room_offer_availability")
                .contains("最多展示4个不同公寓")
                .contains("每个公寓最多2个房型")
                .contains("所有公寓合计最多6个房型选项")
                .contains("Tool 已经把所有公寓合计裁剪为最多6个房型")
                .contains("严禁输出公寓名和位置为空的续行")
                .contains("limitResidences 必须传4")
                .contains("这是上限而不是必须凑满的数量")
                .contains("每次找房最多调用一次 search_room_offers")
                .contains("不得拆成多批")
                .contains("nearbyPlaceKeyword")
                .contains("maxTravelMinutes 必须且只能传25")
                .contains("不得向用户暴露“结构化查询”")
                .contains("我帮你筛到了4个UCL附近")
                .contains("自然友好的结论")
                .contains("用户已经指定一个 roomOfferId")
                .contains("不得把 UCL East 房源混入结果")
                .contains("最终表格最多4行公寓数据")
                .contains("一句自然友好的结论")
                .contains("未查询售罄时不得说“没有售罄房源”")
                .contains("| 公寓 | 位置参考 | 推荐房型 | 可入住时间 | 租期 | 库存 | 匹配说明 |")
                .contains("表格一行代表一个不同公寓")
                .contains("不得输出、推算或暗示具体周价、总价、价格档位、价格范围")
                .contains("普通学校或地标找房时应留空")
                .contains("不得声称精确公里数")
                .contains("UCL Bloomsbury 主校区")
                .contains("未经过地图实时计算")
                .doesNotContain("最终回答应说“本次结构化查询返回”")
                .doesNotContain("Exact pricing and availability must be confirmed")
                .doesNotContain("具体价格及可订状态须由 Londonist 顾问最终确认")
                .doesNotContain("Drapery Place Residence")
                .doesNotContain("每周价格", "预计总价", "quote_room_offer");
    }
}
