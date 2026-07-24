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
                .contains("必须调用合适的 Tool")
                .contains("知识库只用于补充相对稳定的公寓资料")
                .contains("Tool 与知识库冲突时，结构化字段以 Tool 为准")
                .contains("历史报价、房情")
                .contains("search_room_offers")
                .contains("quote_room_offer")
                .contains("最多展示4个不同公寓")
                .contains("每个公寓最多展示2个房型")
                .contains("所有公寓合计最多展示6个房型选项")
                .contains("limitResidences 必须传4")
                .contains("这是上限而不是必须凑满的数量")
                .contains("Drapery Place Residence")
                .contains("同等条件下的排序偏好")
                .contains("相对目标地点明显较远")
                .contains("每次用户找房请求最多调用一次 search_room_offers")
                .contains("不得把候选拆成多批查询")
                .contains("不得为了补入 Drapery Place Residence 而发起第二次查询")
                .contains("不得在 residenceNames 为空时使用 `residenceKeyword=null`")
                .contains("不得把 UCL East 房源混入结果")
                .contains("最终表格最多4行公寓数据")
                .contains("一句话结论")
                .contains("| 公寓 | 位置参考 | 推荐房型 | 可入住时间 | 租期 | 每周价格 | 预计总价 | 库存 | 匹配说明 |")
                .contains("表格一行代表一个不同公寓")
                .contains("只有 Tool 返回 estimatedTotalPrice")
                .contains("通过 residenceNames")
                .contains("模型的一般地理知识只可用于判断大致方位和相对远近")
                .contains("不得声称精确的公里数")
                .contains("UCL Bloomsbury 主校区")
                .contains("不要仅因无法调用地图而拒绝推荐");
    }
}
