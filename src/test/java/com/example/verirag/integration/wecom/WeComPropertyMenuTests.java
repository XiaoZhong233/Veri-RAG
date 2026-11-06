package com.example.verirag.integration.wecom;

import com.example.verirag.mapper.ChatMessageMapper;
import com.example.verirag.mapper.ChatSessionMapper;
import com.example.verirag.tool.PropertyQueryTools;
import com.example.verirag.tool.PropertyRecommendationText;
import com.example.verirag.tool.PropertyQueryTools.*;
import com.example.verirag.tool.ToolCallEventContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class WeComPropertyMenuTests {
    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);
    @SuppressWarnings("unchecked")
    private final ValueOperations<String, String> values = mock(ValueOperations.class);
    private final PropertyQueryTools tools = mock(PropertyQueryTools.class);
    private final ChatMessageMapper messages = mock(ChatMessageMapper.class);
    private final ChatSessionMapper sessions = mock(ChatSessionMapper.class);
    private final TransactionTemplate transactions = mock(TransactionTemplate.class);
    private final WeComPropertyMenu menu = new WeComPropertyMenu(redis, new ObjectMapper(), tools, messages, sessions, transactions);
    private final Map<String, String> cache = new java.util.HashMap<>();

    WeComPropertyMenuTests() {
        when(redis.opsForValue()).thenReturn(values);
        doAnswer(call -> { cache.put(call.getArgument(0), call.getArgument(1)); return null; })
                .when(values).set(anyString(), anyString(), eq(Duration.ofHours(48)));
        when(values.get(anyString())).thenAnswer(call -> cache.get(call.getArgument(0)));
        when(redis.delete(anyString())).thenAnswer(call -> cache.remove(call.getArgument(0)) != null);
        when(sessions.selectById(anyLong())).thenReturn(new com.example.verirag.entity.ChatSession());
        doAnswer(call -> {
            java.util.function.Consumer<org.springframework.transaction.TransactionStatus> action = call.getArgument(0);
            action.accept(mock(org.springframework.transaction.TransactionStatus.class));
            return null;
        }).when(transactions).executeWithoutResult(any());
    }

    @Test
    void offersFourDeterministicMenuItemsAndConciseOverview() throws Exception {
        var reply = create();
        assertEquals(4, reply.items().size());
        assertTrue(reply.overview().contains("4. Property 4"));
        assertTrue(reply.overview().contains("£400/周"));
        assertFalse(reply.overview().contains("完整地址"));
        assertFalse(reply.overview().contains("2027-09-01"));
        assertTrue(reply.items().stream().allMatch(item -> item.id().length() <= 64
                && item.content().getBytes(java.nio.charset.StandardCharsets.UTF_8).length <= 128));
        assertTrue(reply.overview().getBytes(java.nio.charset.StandardCharsets.UTF_8).length <= 2048);
    }

    @Test
    void rechecksClickedResidenceWithEveryOriginalHardCondition() throws Exception {
        var reply = create();
        when(tools.searchRoomOffers(eq("London"), isNull(), eq("Property 2"), eq("UCL"), eq(25), eq("TUBE,WALK"),
                eq("2026-12-01"), eq("2026-12-01"), eq(26), eq("Studio"),
                argThat(b -> b != null && b.compareTo(new BigDecimal("500")) == 0), eq(false), eq(4)))
                .thenReturn(result(List.of(group(2))));
        var detail = menu.resolve("kf", "user", 7L, reply.items().get(1).id());
        assertNotNull(detail);
        assertTrue(detail.answer().startsWith("Property 2"));
        assertTrue(detail.answer().contains("2026-12-01"));
        assertTrue(detail.answer().contains("26周"));
        assertFalse(detail.answer().contains("重新核验"));
        assertFalse(detail.answer().contains("业务数据快照"));
        assertFalse(detail.answer().contains("推荐理由："));
        assertFalse(detail.answer().contains("优先级"));
        assertFalse(detail.answer().contains("销售排序"));
        verify(tools).searchRoomOffers(eq("London"), isNull(), eq("Property 2"), eq("UCL"), eq(25), eq("TUBE,WALK"),
                eq("2026-12-01"), eq("2026-12-01"), eq(26), eq("Studio"),
                argThat(b -> b != null && b.compareTo(new BigDecimal("500")) == 0), eq(false), eq(4));
        verify(transactions).executeWithoutResult(any());
        var saved = org.mockito.ArgumentCaptor.forClass(com.example.verirag.entity.ChatMessage.class);
        verify(messages, times(2)).insert(saved.capture());
        assertEquals("查看 Property 2", saved.getAllValues().getFirst().getContent());
        assertEquals(detail.answer(), saved.getAllValues().getLast().getContent());
    }

    @Test
    void isolatesCustomersAccountsAndSessionsAndHandlesExpiry() throws Exception {
        String id = create().items().getFirst().id();
        assertNull(menu.resolve("kf", "another-user", 7L, id));
        assertNull(menu.resolve("another-kf", "user", 7L, id));
        assertNull(menu.resolve("kf", "user", 8L, id));
        assertNull(menu.resolve("kf", "user", 7L, "property:invalid:0"));
        cache.clear();
        assertNull(menu.resolve("kf", "user", 7L, id));
        verifyNoInteractions(tools);
    }

    @Test
    void doesNotReuseStaleAvailabilityWhenRoomNoLongerMatches() throws Exception {
        String id = create().items().getFirst().id();
        when(tools.searchRoomOffers(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(result(List.of()));
        var detail = menu.resolve("kf", "user", 7L, id);
        assertTrue(detail.answer().contains("目前没有符合你需求"));
        assertFalse(detail.answer().contains("£400"));
    }

    @Test
    void keepsSoldOutAndUnknownStatusesVisibleAndWarnsAboutMissingDates() {
        var original = group(1).rooms().getFirst();
        var soldOut = new RoomMatch(original.roomOfferId(), "code", "Studio", "Studio",
                original.earliestStartDate(), original.latestEndDate(), null, null, "SOLD_OUT", 0,
                null, BigDecimal.valueOf(400), "GBP", null, null);
        var g = group(1);
        var group = new ResidenceOfferGroup(g.residenceId(), g.residenceSourceId(), g.residenceName(),
                g.city(), g.address(), g.station(), g.zone(), null, null, null, List.of(), List.of(soldOut));
        var r = new RoomOfferSearchResult("London", null, List.of(), null, null, null, null, null,
                "REFERENCE", 1, 0, 1, List.of(group), List.of());
        String overview = PropertyRecommendationText.overview(r, true);
        assertTrue(overview.contains("售罄"));
        assertFalse(overview.contains("什么时候入住"));
        String heading = PropertyRecommendationText.menuHeading(r, true);
        assertTrue(heading.contains("你预计什么时候入住、打算住多久呀？"));
        assertEquals(1, heading.split("告诉小伦同学", -1).length - 1);
        assertFalse(overview.contains("尚未指定"));
        assertFalse(overview.contains("待核验"));
        assertFalse(overview.contains("可预订"));
    }

    @Test
    void handlesEnglishAndEmptyResultsWithoutInventingProperties() throws Exception {
        assertTrue(PropertyRecommendationText.overview(result(List.of(group(1))), false).contains("matching properties"));
        assertNull(menu.create("kf", "user", "msg", 7L, "Find a room",
                new ToolCallEventContext.Event(ToolCallEventContext.Phase.COMPLETED,
                        "search_room_offers", result(List.of()), filters())));
        assertTrue(menu.lastContext("kf", "user", 7L).containsKey("stayWeeks"));
        assertFalse(cache.keySet().stream().anyMatch(key -> key.contains(":active:")));
    }

    @Test
    void detailsDoNotQueryFacilitiesForRecommendationReasons() throws Exception {
        var reply = create();
        when(tools.searchRoomOffers(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(result(List.of(group(2))));
        String answer = menu.resolve("kf", "user", 7L, reply.items().get(1).id()).answer();
        assertFalse(answer.contains("推荐理由"));
        verify(tools, never()).getResidenceDetails(anyString(), any());
    }

    @Test
    void invalidatesOldMenuOnNewQuestionButKeepsConditionsForShortFollowUp() throws Exception {
        String id = create().items().getFirst().id();
        menu.invalidate("kf", "user");
        assertNull(menu.resolve("kf", "user", 7L, id));
        var context = menu.lastContext("kf", "user", 7L);
        assertEquals("UCL", context.get("nearbyPlaceKeyword"));
        assertEquals("2026-12-01", context.get("startDateFrom"));
        assertEquals("TUBE,WALK", context.get("preferredTravelModes"));
        assertTrue(menu.lastContext("kf", "user", 8L).isEmpty());
        verifyNoInteractions(tools);
    }

    @Test
    void latestQueryReplacesOldMenuEvenWhenNewBudgetHasNoResults() throws Exception {
        String old = create().items().getFirst().id();
        var changed = new java.util.HashMap<>(filters());
        changed.put("maxWeeklyPrice", new BigDecimal("300"));
        assertNull(menu.create("kf", "user", "new-budget", 7L, "预算改成300", new ToolCallEventContext.Event(
                ToolCallEventContext.Phase.COMPLETED, "search_room_offers", result(List.of()), changed)));
        assertNull(menu.resolve("kf", "user", 7L, old));
        assertEquals(300, ((Number) menu.lastContext("kf", "user", 7L).get("maxWeeklyPrice")).intValue());
        verifyNoInteractions(tools);
    }

    @Test
    void newerSuccessfulRecommendationInvalidatesPreviousToken() throws Exception {
        String old = create().items().getFirst().id();
        var newer = menu.create("kf", "user", "new-query", 7L, "换个区域", new ToolCallEventContext.Event(
                ToolCallEventContext.Phase.COMPLETED, "search_room_offers", result(List.of(group(2))), filters()));
        assertNotEquals(old, newer.items().getFirst().id());
        assertNull(menu.resolve("kf", "user", 7L, old));
        verifyNoInteractions(tools);
    }

    private WeComPropertyMenu.Recommendation create() throws Exception {
        return menu.create("kf", "user", "message-1", 7L, "UCL附近12月1日起租半年，500镑以内Studio，地铁优先",
                new ToolCallEventContext.Event(ToolCallEventContext.Phase.COMPLETED,
                        "search_room_offers", result(List.of(group(1), group(2), group(3), group(4))), filters()));
    }

    private Map<String, Object> filters() {
        return Map.of("city", "London", "nearbyPlaceKeyword", "UCL", "maxTravelMinutes", 25,
                "preferredTravelModes", "TUBE,WALK", "startDateFrom", "2026-12-01", "startDateTo", "2026-12-01",
                "stayWeeks", 26, "rootTypes", "Studio", "maxWeeklyPrice", new BigDecimal("500"), "includeSoldOut", false);
    }

    private static RoomOfferSearchResult result(List<ResidenceOfferGroup> groups) {
        return new RoomOfferSearchResult("London", null, List.of(), "UCL", 25,
                LocalDate.parse("2026-12-01"), LocalDate.parse("2026-12-01"), 26, "REFERENCE",
                groups.size(), groups.size(), 0, groups, List.of());
    }

    private static ResidenceOfferGroup group(long id) {
        var room = new RoomMatch(id, "code", "Bronze Studio", "Studio", LocalDate.parse("2026-11-01"),
                LocalDate.parse("2027-09-01"), LocalDate.parse("2026-12-01"), LocalDate.parse("2027-06-01"),
                "AVAILABLE", 2, null, BigDecimal.valueOf(400), "GBP", BigDecimal.valueOf(10400), null);
        return new ResidenceOfferGroup(id, "source", "Property " + id, "London", "完整地址", "Station", "1",
                null, null, null, List.of(), List.of(room));
    }
}
