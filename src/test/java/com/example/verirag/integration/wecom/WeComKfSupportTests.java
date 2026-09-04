package com.example.verirag.integration.wecom;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WeComKfSupportTests {

    @Test
    void safelyReadsCallbackXml() {
        String xml = "<xml><Event><![CDATA[kf_msg_or_event]]></Event>"
                + "<Token><![CDATA[token-value]]></Token></xml>";

        assertEquals("kf_msg_or_event", WeComXml.value(xml, "Event"));
        assertEquals("token-value", WeComXml.value(xml, "Token"));
        assertEquals("", WeComXml.value(xml, "OpenKfId"));
    }

    @Test
    void rejectsDoctypeAndExternalEntities() {
        String xml = "<!DOCTYPE foo [<!ENTITY xxe SYSTEM 'file:///etc/passwd'>]>"
                + "<xml><Token>&xxe;</Token></xml>";

        assertThrows(IllegalArgumentException.class,
                () -> WeComXml.value(xml, "Token"));
    }

    @Test
    void truncatesReplyOnUtf8Boundary() {
        String result = WeComKfMessageService.truncateUtf8("伦敦学生公寓推荐", 12);

        assertTrue(result.getBytes(java.nio.charset.StandardCharsets.UTF_8).length <= 12);
        assertTrue(result.endsWith("..."));
    }

    @Test
    void splitsLongReplyOnUtf8BoundaryWithoutDroppingContent() {
        String source = "伦敦学生公寓参考价格和库存确认。".repeat(20);

        java.util.List<String> parts = WeComKfMessageService.splitUtf8(source, 48);

        assertTrue(parts.size() > 1);
        assertTrue(parts.stream().allMatch(part ->
                part.getBytes(java.nio.charset.StandardCharsets.UTF_8).length <= 48));
        assertEquals(source, String.join("", parts));
    }

    @Test
    void convertsMarkdownTableToReadableWeComText() {
        String markdown = """
                ## 推荐房源

                | 公寓 | 距离 | 链接 |
                | --- | --- | --- |
                | Chapter | 10分钟 | [查看](https://example.com) |
                """;

        String result = WeComPlainTextFormatter.format(markdown);

        assertEquals("""
                推荐房源

                1. Chapter
                   距离：10分钟
                   链接：查看：https://example.com""", result);
    }

    @Test
    void removesCommonInlineMarkdownForWeCom() {
        String result = WeComPlainTextFormatter.format(
                "**重点**：`Studio`\n- [详情](https://example.com)");

        assertEquals("重点：Studio\n• 详情：https://example.com", result);
    }
}
