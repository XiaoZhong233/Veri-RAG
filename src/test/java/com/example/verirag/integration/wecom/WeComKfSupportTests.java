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
}
