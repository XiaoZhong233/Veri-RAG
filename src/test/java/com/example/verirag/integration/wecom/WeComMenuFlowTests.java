package com.example.verirag.integration.wecom;

import com.example.verirag.config.WeComKfProperties;
import com.example.verirag.dto.ChatAskResult;
import com.example.verirag.mapper.WeComConversationMapper;
import com.example.verirag.mapper.WeComKfPendingMessageMapper;
import com.example.verirag.mapper.WeComKfStateMapper;
import com.example.verirag.observability.WeComKfMetrics;
import com.example.verirag.service.ChatService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.core.task.TaskExecutor;
import org.springframework.scheduling.TaskScheduler;

import java.util.List;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class WeComMenuFlowTests {
    private final WeComKfApiClient api = mock(WeComKfApiClient.class);
    private final WeComKfStateMapper state = mock(WeComKfStateMapper.class);
    private final WeComConversationMapper conversations = mock(WeComConversationMapper.class);
    private final ChatService chat = mock(ChatService.class);
    private final WeComPropertyMenu menu = mock(WeComPropertyMenu.class);
    private final ObjectMapper mapper = new ObjectMapper();
    private final WeComKfMessageService service = new WeComKfMessageService(new WeComKfProperties(), api,
            state, mock(WeComKfPendingMessageMapper.class), conversations, chat,
            mock(TaskScheduler.class), mock(TaskExecutor.class), mock(WeComKfMetrics.class), mapper,
            mock(WeComCustomerService.class), menu);

    WeComMenuFlowTests() {
        when(api.getServiceState("kf", "user")).thenReturn(1);
        when(conversations.selectSessionId(eq("kf:kf"), eq("user"), any())).thenReturn(7L);
        when(menu.isMenu(startsWith("property:"))).thenReturn(true);
    }

    @Test
    void sendsOverviewAndMenuInsteadOfLongModelAnswer() throws Exception {
        var result = new ChatAskResult();
        result.setSessionId(7L);
        result.setAnswer("原来冗长的模型回答");
        when(chat.ask(any(), any())).thenReturn(result);
        var items = List.of(new WeComKfApiClient.MenuItem("property:token:0", "查看 Great Court"));
        when(menu.create(eq("kf"), eq("user"), eq("msg"), eq(7L), anyString(), isNull()))
                .thenReturn(new WeComPropertyMenu.Recommendation("简短概览", "查看详情", items));
        service.processMessage(mapper.readTree(message("找房", "")));
        verify(api).sendText(eq("kf"), eq("user"), anyString(), eq("简短概览"));
        verify(api).sendMenu(eq("kf"), eq("user"), anyString(), eq("查看详情"), eq(items));
        verify(api, never()).sendText(any(), any(), any(), eq("原来冗长的模型回答"));
    }

    @Test
    void failedMenuDoesNotRepeatOverviewOrModelCall() throws Exception {
        var result = new ChatAskResult();
        result.setSessionId(7L);
        result.setAnswer("模型回答");
        when(chat.ask(any(), any())).thenReturn(result);
        var items = List.of(new WeComKfApiClient.MenuItem("property:token:0", "查看 Great Court"));
        when(menu.create(any(), any(), any(), any(), any(), any()))
                .thenReturn(new WeComPropertyMenu.Recommendation("简短概览", "查看详情", items));
        doThrow(new IllegalStateException("menu rejected")).when(api).sendMenu(any(), any(), any(), any(), any());
        service.processMessage(mapper.readTree(message("找房", "")));
        verify(chat, times(1)).ask(any(), any());
        verify(api, times(1)).sendText(any(), any(), any(), eq("简短概览"));
        verify(api, times(3)).sendMenu(any(), any(), any(), any(), any());
        verify(api).sendText(any(), any(), any(), contains("菜单暂不可用"));
        verify(state).insertProcessed("msg", "kf", "user", "text");
    }

    @Test
    void routesClickByMenuIdAndNeverAsksModelToGuess() throws Exception {
        when(menu.resolve("kf", "user", 7L, "property:token:0"))
                .thenReturn(new WeComPropertyMenu.Detail(7L, "查看 Great Court", "已核验的房型详情"));
        service.processMessage(mapper.readTree(message("查看 Great Court", "property:token:0")));
        verify(api).sendText(eq("kf"), eq("user"), anyString(), eq("已核验的房型详情"));
        verifyNoInteractions(chat);
        verify(state).insertProcessed("msg", "kf", "user", "text");
    }

    @Test
    void expiredMenuExplainsHowToRestartWithoutQueryingModel() throws Exception {
        service.processMessage(mapper.readTree(message("查看 Great Court", "property:token:0")));
        verify(api).sendText(eq("kf"), eq("user"), anyString(), contains("推荐菜单已失效"));
        verifyNoInteractions(chat);
    }

    @Test
    void doesNotAnswerMenuClicksDuringHumanService() throws Exception {
        when(api.getServiceState("kf", "user")).thenReturn(3);
        service.processMessage(mapper.readTree(message("查看 Great Court", "property:token:0")));
        verifyNoInteractions(chat);
        verify(menu, never()).resolve(any(), any(), any(), any());
        verify(api, never()).sendText(any(), any(), any(), any());
    }

    private String message(String content, String id) {
        var message = mapper.createObjectNode().put("msgid", "msg").put("origin", 3)
                .put("open_kfid", "kf").put("external_userid", "user").put("msgtype", "text");
        message.putObject("text").put("content", content).put("menu_id", id);
        return message.toString();
    }
}
