package com.example.verirag.integration.wecom;

import com.example.verirag.config.WeComKfProperties;
import com.example.verirag.mapper.WeComConversationMapper;
import com.example.verirag.mapper.WeComKfPendingMessageMapper;
import com.example.verirag.mapper.WeComKfStateMapper;
import com.example.verirag.observability.WeComKfMetrics;
import com.example.verirag.service.ChatService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.core.task.TaskExecutor;
import org.springframework.scheduling.TaskScheduler;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;

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

    @Test
    void keepsAssistantStateWhenCustomerRequestsHuman() throws Exception {
        WeComKfApiClient apiClient = mock(WeComKfApiClient.class);
        WeComKfStateMapper stateMapper = mock(WeComKfStateMapper.class);
        WeComKfPendingMessageMapper pendingMapper = mock(WeComKfPendingMessageMapper.class);
        WeComKfProperties properties = new WeComKfProperties();
        WeComKfMessageService service = service(
                properties, apiClient, stateMapper, pendingMapper);
        when(apiClient.getServiceState("kf-1", "user-1")).thenReturn(1);

        service.processMessage(new ObjectMapper().readTree("""
                {"msgid":"message-1","open_kfid":"kf-1","external_userid":"user-1",
                 "origin":3,"msgtype":"text","text":{"content":"转人工"}}
                """));

        verify(apiClient).sendText(eq("kf-1"), eq("user-1"), startsWith("vr_"),
                eq(properties.getHandoffMessage()));
        verify(apiClient, never()).transitionToEnded("kf-1", "user-1");
        verify(stateMapper).insertProcessed("message-1", "kf-1", "user-1", "text");
    }

    @Test
    void assignsHumanRequestToAnActiveServicer() throws Exception {
        WeComKfApiClient apiClient = mock(WeComKfApiClient.class);
        WeComKfStateMapper stateMapper = mock(WeComKfStateMapper.class);
        WeComKfPendingMessageMapper pendingMapper = mock(WeComKfPendingMessageMapper.class);
        WeComKfProperties properties = new WeComKfProperties();
        WeComKfMessageService service = service(
                properties, apiClient, stateMapper, pendingMapper);
        when(apiClient.getServiceState("kf-1", "user-1")).thenReturn(1);
        when(apiClient.listServicers("kf-1")).thenReturn(java.util.List.of(
                new WeComKfApiClient.KfServicer("advisor-1", 0L, 0)));
        when(apiClient.transitionToHuman("kf-1", "user-1", "advisor-1"))
                .thenReturn("handoff-code");

        service.processMessage(new ObjectMapper().readTree("""
                {"msgid":"message-human","open_kfid":"kf-1","external_userid":"user-1",
                 "origin":3,"msgtype":"text","text":{"content":"转人工"}}
                """));

        verify(apiClient).transitionToHuman("kf-1", "user-1", "advisor-1");
        verify(apiClient).sendEventText(eq("handoff-code"), startsWith("vr_"),
                eq(properties.getHandoffSuccessMessage()));
        verify(apiClient, never()).sendText(
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void sendsConfiguredWelcomeMessageForEnterSessionEvent() throws Exception {
        WeComKfApiClient apiClient = mock(WeComKfApiClient.class);
        WeComKfStateMapper stateMapper = mock(WeComKfStateMapper.class);
        WeComKfPendingMessageMapper pendingMapper = mock(WeComKfPendingMessageMapper.class);
        WeComKfProperties properties = new WeComKfProperties();
        WeComKfMessageService service = service(
                properties, apiClient, stateMapper, pendingMapper);

        service.processMessage(new ObjectMapper().readTree("""
                {"msgid":"message-welcome","origin":0,"msgtype":"event","event":{
                 "event_type":"enter_session","open_kfid":"kf-1",
                 "external_userid":"user-1","welcome_code":"welcome-code"}}
                """));

        verify(apiClient).sendEventText(eq("welcome-code"), startsWith("vr_"),
                eq(properties.getWelcomeMessage()));
        verify(stateMapper).insertProcessed(
                "message-welcome", "kf-1", "user-1", "event");
    }

    @Test
    void endsLegacyWaitingSessionAndUsesEventMessage() throws Exception {
        WeComKfApiClient apiClient = mock(WeComKfApiClient.class);
        WeComKfStateMapper stateMapper = mock(WeComKfStateMapper.class);
        WeComKfPendingMessageMapper pendingMapper = mock(WeComKfPendingMessageMapper.class);
        WeComKfProperties properties = new WeComKfProperties();
        WeComKfMessageService service = service(
                properties, apiClient, stateMapper, pendingMapper);
        when(apiClient.getServiceState("kf-1", "user-1")).thenReturn(2);
        when(apiClient.transitionToEnded("kf-1", "user-1")).thenReturn("event-code");

        service.processMessage(new ObjectMapper().readTree("""
                {"msgid":"message-2","open_kfid":"kf-1","external_userid":"user-1",
                 "origin":3,"msgtype":"text","text":{"content":"还在吗"}}
                """));

        verify(apiClient).sendEventText(eq("event-code"), startsWith("vr_"),
                eq(properties.getStuckSessionRecoveryMessage()));
        verify(apiClient, never()).sendText(
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString());
        verify(stateMapper).insertProcessed("message-2", "kf-1", "user-1", "text");
    }

    private static WeComKfMessageService service(
            WeComKfProperties properties,
            WeComKfApiClient apiClient,
            WeComKfStateMapper stateMapper,
            WeComKfPendingMessageMapper pendingMapper) {
        return new WeComKfMessageService(
                properties,
                apiClient,
                stateMapper,
                pendingMapper,
                mock(WeComConversationMapper.class),
                mock(ChatService.class),
                mock(TaskScheduler.class),
                mock(TaskExecutor.class),
                mock(WeComKfMetrics.class),
                new ObjectMapper());
    }
}
