package com.example.verirag.integration.wecom;

import com.example.verirag.config.WeComKfProperties;
import com.example.verirag.mapper.WeComConversationMapper;
import com.example.verirag.mapper.WeComKfPendingMessageMapper;
import com.example.verirag.mapper.WeComKfStateMapper;
import com.example.verirag.observability.WeComKfMetrics;
import com.example.verirag.service.ChatService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.core.task.TaskExecutor;
import org.springframework.scheduling.TaskScheduler;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doThrow;
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
    void requiresSeparateContactsSecretForMemberDirectory() {
        WeComKfProperties properties = new WeComKfProperties();
        properties.setCorpId("corp-1");
        WeComKfApiClient apiClient = new WeComKfApiClient(
                properties, new ObjectMapper());

        IllegalStateException error = assertThrows(
                IllegalStateException.class, apiClient::listVisibleMemberIds);

        assertTrue(error.getMessage().contains("WECOM_CONTACTS_SECRET"));
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
    void keepsHumanAssignmentWhenOnlyHandoffNoticeFails() throws Exception {
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
        doThrow(new IllegalStateException("notice expired")).when(apiClient)
                .sendEventText(eq("handoff-code"), startsWith("vr_"),
                        eq(properties.getHandoffSuccessMessage()));

        service.processMessage(new ObjectMapper().readTree("""
                {"msgid":"message-notice-failed","open_kfid":"kf-1",
                 "external_userid":"user-1","origin":3,"msgtype":"text",
                 "text":{"content":"转人工"}}
                """));

        verify(apiClient).transitionToHuman("kf-1", "user-1", "advisor-1");
        verify(stateMapper).insertProcessed(
                "message-notice-failed", "kf-1", "user-1", "text");
    }

    @Test
    void fallsBackToAssistantWhenHumanTransitionFails() throws Exception {
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
                .thenThrow(new IllegalStateException("handoff rejected"));

        service.processMessage(new ObjectMapper().readTree("""
                {"msgid":"message-handoff-failed","open_kfid":"kf-1",
                 "external_userid":"user-1","origin":3,"msgtype":"text",
                 "text":{"content":"转人工"}}
                """));

        verify(apiClient).sendText(eq("kf-1"), eq("user-1"), startsWith("vr_"),
                eq(properties.getHandoffMessage()));
        verify(stateMapper).insertProcessed(
                "message-handoff-failed", "kf-1", "user-1", "text");
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
    void doesNotAttemptIllegalEndFromWaitingState() throws Exception {
        WeComKfApiClient apiClient = mock(WeComKfApiClient.class);
        WeComKfStateMapper stateMapper = mock(WeComKfStateMapper.class);
        WeComKfPendingMessageMapper pendingMapper = mock(WeComKfPendingMessageMapper.class);
        WeComKfProperties properties = new WeComKfProperties();
        WeComKfMessageService service = service(
                properties, apiClient, stateMapper, pendingMapper);
        when(apiClient.getServiceState("kf-1", "user-1")).thenReturn(2);

        JsonNode message = new ObjectMapper().readTree("""
                {"msgid":"message-2","open_kfid":"kf-1","external_userid":"user-1",
                 "origin":3,"msgtype":"text","text":{"content":"还在吗"}}
                """);

        assertThrows(IllegalStateException.class, () -> service.processMessage(message));

        verify(apiClient, never()).transitionToEnded("kf-1", "user-1");
        verify(stateMapper, never()).insertProcessed("message-2", "kf-1", "user-1", "text");
    }

    @Test
    void acceptsConcurrentChangeAfterWaitingAssignmentIsRejected() throws Exception {
        WeComKfApiClient apiClient = mock(WeComKfApiClient.class);
        WeComKfStateMapper stateMapper = mock(WeComKfStateMapper.class);
        WeComKfPendingMessageMapper pendingMapper = mock(WeComKfPendingMessageMapper.class);
        WeComKfProperties properties = new WeComKfProperties();
        WeComKfMessageService service = service(
                properties, apiClient, stateMapper, pendingMapper);
        when(apiClient.getServiceState("kf-1", "user-1")).thenReturn(2, 3);
        when(apiClient.listServicers("kf-1")).thenReturn(java.util.List.of(
                new WeComKfApiClient.KfServicer("advisor-1", 0L, 0)));
        when(apiClient.transitionToHuman("kf-1", "user-1", "advisor-1"))
                .thenThrow(new IllegalStateException("handoff rejected"));

        service.processMessage(new ObjectMapper().readTree("""
                {"msgid":"message-waiting-rejected","open_kfid":"kf-1",
                 "external_userid":"user-1","origin":3,"msgtype":"text",
                 "text":{"content":"还在吗"}}
                """));

        verify(apiClient, never()).transitionToEnded("kf-1", "user-1");
        verify(stateMapper).insertProcessed(
                "message-waiting-rejected", "kf-1", "user-1", "text");
    }

    @Test
    void doesNotHandoffJustBecauseTextContainsKeyword() throws Exception {
        var api = mock(WeComKfApiClient.class);
        var state = mock(WeComKfStateMapper.class);
        var pending = mock(WeComKfPendingMessageMapper.class);
        var chat = mock(ChatService.class);
        var result = new com.example.verirag.dto.ChatAskResult();
        result.setAnswer("好的，继续为您查找房源。");
        when(chat.ask(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any())).thenReturn(result);
        when(api.getServiceState("kf-1", "user-1")).thenReturn(1);
        var service = service(new WeComKfProperties(), api, state, pending, chat, Runnable::run);
        service.processMessage(new ObjectMapper().readTree("""
                {"msgid":"negative","open_kfid":"kf-1","external_userid":"user-1",
                 "origin":3,"msgtype":"text","text":{"content":"不要转人工，继续帮我找房"}}
                """));
        verify(api, never()).listServicers(org.mockito.ArgumentMatchers.anyString());
        verify(chat).ask(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.argThat(
                req -> req.isAllowHumanHandoff() && req.getQuestion().contains("不要转人工")));
        verify(api).sendText(eq("kf-1"), eq("user-1"), startsWith("vr_"), eq(result.getAnswer()));
    }

    @Test
    void modelHandoffDoesNotRequireOldKeywords() throws Exception {
        var api = mock(WeComKfApiClient.class);
        var state = mock(WeComKfStateMapper.class);
        var pending = mock(WeComKfPendingMessageMapper.class);
        var service = service(new WeComKfProperties(), api, state, pending);
        when(api.getServiceState("kf-1", "user-1")).thenReturn(1);
        when(api.listServicers("kf-1")).thenReturn(java.util.List.of(
                new WeComKfApiClient.KfServicer("advisor-1", 0L, 0)));
        service.processMessage(new ObjectMapper().readTree("""
                {"msgid":"human-semantic","open_kfid":"kf-1","external_userid":"user-1",
                 "origin":3,"msgtype":"text","text":{"content":"我想和真人聊一下"}}
                """));
        verify(api).transitionToHuman("kf-1", "user-1", "advisor-1");
    }

    @Test
    void malformedPayloadDoesNotStopFollowingMessages() {
        var api = mock(WeComKfApiClient.class);
        var state = mock(WeComKfStateMapper.class);
        var pending = mock(WeComKfPendingMessageMapper.class);
        when(pending.listPendingMessages(eq(100), org.mockito.ArgumentMatchers.anyList())).thenReturn(java.util.List.of(
                new WeComKfPendingMessageMapper.PendingMessage("bad", "not-json"),
                new WeComKfPendingMessageMapper.PendingMessage("good", "{\"msgid\":\"good\",\"origin\":0}")));
        var service = service(new WeComKfProperties(), api, state, pending, mock(ChatService.class), Runnable::run);
        service.recoverPendingMessages();
        verify(pending).recordFailure("bad");
        verify(pending).deletePending("good");
        verify(pending).deleteRetry("good");
    }

    @Test
    void rejectedExecutorReleasesMessageForLaterRecovery() {
        var api = mock(WeComKfApiClient.class);
        var state = mock(WeComKfStateMapper.class);
        var pending = mock(WeComKfPendingMessageMapper.class);
        when(pending.listPendingMessages(eq(100), org.mockito.ArgumentMatchers.anyList())).thenReturn(java.util.List.of(
                new WeComKfPendingMessageMapper.PendingMessage("retry", "{\"msgid\":\"retry\",\"origin\":0}")));
        TaskExecutor executor = task -> { throw new java.util.concurrent.RejectedExecutionException("busy"); };
        var service = service(new WeComKfProperties(), api, state, pending, mock(ChatService.class), executor);
        service.recoverPendingMessages();
        service.recoverPendingMessages();
        verify(pending, org.mockito.Mockito.times(2)).recordFailure("retry");
        verify(pending, org.mockito.Mockito.times(2)).listPendingMessages(100, java.util.List.of());
    }

    private static WeComKfMessageService service(
            WeComKfProperties properties,
            WeComKfApiClient apiClient,
            WeComKfStateMapper stateMapper,
            WeComKfPendingMessageMapper pendingMapper) {
        ChatService chatService = mock(ChatService.class);
        com.example.verirag.dto.ChatAskResult result = new com.example.verirag.dto.ChatAskResult();
        result.setHumanHandoff(true);
        try {
            when(chatService.ask(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
                    .thenReturn(result);
        } catch (Exception ex) {
            throw new AssertionError(ex);
        }
        return service(properties, apiClient, stateMapper, pendingMapper, chatService, mock(TaskExecutor.class));
    }

    private static WeComKfMessageService service(
            WeComKfProperties properties, WeComKfApiClient apiClient,
            WeComKfStateMapper stateMapper, WeComKfPendingMessageMapper pendingMapper,
            ChatService chatService, TaskExecutor executor) {
        return new WeComKfMessageService(
                properties,
                apiClient,
                stateMapper,
                pendingMapper,
                mock(WeComConversationMapper.class),
                chatService,
                mock(TaskScheduler.class),
                executor,
                mock(WeComKfMetrics.class),
                new ObjectMapper());
    }
}
