package com.example.verirag.integration.wecom;

import com.example.verirag.entity.WeComCustomer;
import com.example.verirag.entity.WeComCustomerMessage;
import com.example.verirag.mapper.WeComCustomerMapper;
import com.example.verirag.controller.WeComKfAdminController;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.security.access.prepost.PreAuthorize;
import java.time.LocalDateTime;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class WeComCustomerServiceTests {
    private final WeComCustomerMapper mapper=mock(WeComCustomerMapper.class);
    private final WeComKfApiClient api=mock(WeComKfApiClient.class);
    private final WeComCustomerService service=new WeComCustomerService(mapper,api,mock(ChatClient.class));

    @Test void allCustomerEndpointsRequireAdmin() {
        assertThat(WeComKfAdminController.class.getAnnotation(PreAuthorize.class).value()).isEqualTo("hasRole('ADMIN')");
    }
    @Test void profileFailureKeepsExistingIdentityAndHistoryAvailable() {
        var customer=new WeComCustomer(); customer.setId(1L); customer.setExternalUserId("external"); customer.setNickname("原昵称");
        when(mapper.find(1L)).thenReturn(customer);
        when(api.customer("external")).thenThrow(new IllegalStateException("API unavailable"));
        var detail=service.refreshProfile(1L);
        assertThat(detail.customer().getNickname()).isEqualTo("原昵称");
        assertThat(detail.profileWarning()).isNotBlank();
        verify(mapper,never()).profile(anyLong(),anyString(),anyString());
    }
    @Test void preservesCustomerAndHumanTextsWithDistinctRoles() throws Exception {
        service.observe(new ObjectMapper().readTree("""
            {"msgid":"m1","open_kfid":"kf","external_userid":"e1","origin":3,"msgtype":"text","text":{"content":"预算400"}}
            """));
        service.observe(new ObjectMapper().readTree("""
            {"msgid":"m2","open_kfid":"kf","external_userid":"e1","origin":5,"msgtype":"text","text":{"content":"收到，我帮您查"}}
            """));
        verify(mapper).archive(eq("kf"),eq("e1"),eq("m1"),eq("CUSTOMER"),eq("text"),eq("预算400"),any(LocalDateTime.class));
        verify(mapper).archive(eq("kf"),eq("e1"),eq("m2"),eq("HUMAN"),eq("text"),eq("收到，我帮您查"),any(LocalDateTime.class));
    }
    @Test void returnsHistoryChronologicallyWithCursor() {
        when(mapper.find(1L)).thenReturn(new WeComCustomer());
        var newer=new WeComCustomerMessage(); newer.setId(20L);
        var older=new WeComCustomerMessage(); older.setId(19L);
        when(mapper.messages(1L,0,51)).thenReturn(List.of(newer,older));
        var history=service.history(1L,0,false);
        assertThat(history.records()).containsExactly(older,newer);
        assertThat(history.nextBefore()).isEqualTo(19L);
    }
    @Test void rejectsUnsafeAvatarSchemes() {
        assertThat(WeComCustomerService.safeAvatar("javascript:alert(1)")).isEmpty();
        assertThat(WeComCustomerService.safeAvatar("https://user:password@example.com/image")).isEmpty();
        assertThat(WeComCustomerService.safeAvatar("https://example.com/avatar.png")).isEqualTo("https://example.com/avatar.png");
        assertThat(WeComCustomerService.safeAvatar("http://example.com/avatar.png")).isEqualTo("https://example.com/avatar.png");
    }
    @Test void serviceSummaryIsSavedWithoutSendingAnythingToCustomer() {
        var client=mock(ChatClient.class,RETURNS_DEEP_STUBS);
        var service=new WeComCustomerService(mapper,api,client);
        var customer=new WeComCustomer(); customer.setId(1L); customer.setNickname("客户昵称");
        when(mapper.find(1L)).thenReturn(customer);
        var message=new WeComCustomerMessage(); message.setId(2L); message.setSentAt(LocalDateTime.now());
        message.setSpeaker("CUSTOMER"); message.setContent("预算改成400");
        when(mapper.messages(1L,0,100)).thenReturn(List.of(message));
        when(mapper.legacy(1L,0,null,50)).thenReturn(List.of());
        when(client.prompt().system(anyString()).user(anyString())
                .options(any(org.springframework.ai.openai.OpenAiChatOptions.Builder.class)).call().content())
                .thenReturn("最新预算400，入住日期待确认");
        service.summarize(1L);
        verify(mapper).summary(eq(1L),eq("最新预算400，入住日期待确认"),any(LocalDateTime.class));
        verifyNoInteractions(api);
    }
}
