package com.example.verirag.tool;

import com.example.verirag.tool.PropertyQueryTools.*;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class PropertyRecommendationTextTests {
    @Test
    void nearbyOverviewShowsRealModeAndMinutesWithinThreeLinesPerProperty() {
        var group = group(List.of(room("AVAILABLE", 2)), true);
        String out = PropertyRecommendationText.overview(result(group, true, null), true, "UCL附近", Map.of());
        assertTrue(out.contains("UCL：地铁约15–20分钟"));
        assertTrue(out.contains("可预订（剩余2间）"));
        assertEquals(1, occurrences(out, "你打算住多久"));
        assertEquals(3, out.split("\n\n")[1].split("\n").length);
    }

    @Test
    void budgetFocusKeepsPriceAndRoomTypeWithoutExtraDateOrCommuteLines() {
        String out = PropertyRecommendationText.overview(result(group(List.of(room("AVAILABLE", 2)), true), true, 26),
                true, "预算优先", Map.of("maxWeeklyPrice", 500));
        assertTrue(out.contains("Studio｜参考 £400/周"));
        assertFalse(out.contains("地铁"));
        assertFalse(out.contains("2026-12-01"));
    }

    @Test
    void dateFocusShowsRealStartAndEndAndNoInventedCommute() {
        String out = PropertyRecommendationText.overview(result(group(List.of(room("AVAILABLE", 2)), false), false, 26), true);
        assertTrue(out.contains("入住：2026-12-01｜退房：2027-06-01"));
        assertFalse(out.contains("分钟"));
    }

    @Test
    void browsingDetailsNeverRepeatsMissingCriteriaAndCombinesSharedDates() {
        var group = group(List.of(room("AVAILABLE", 2), room("LIMITED", null)), true);
        String out = PropertyRecommendationText.details(group, result(group, true, null), true);
        assertFalse(out.contains("住多久"));
        assertFalse(out.contains("什么时候入住"));
        assertFalse(out.contains("推荐理由"));
        assertEquals(1, occurrences(out, "入住：2026-12-01"));
        assertEquals(1, occurrences(out, "地铁约"));
        assertFalse(out.contains("库存紧张"));
    }

    @Test
    void addsAtMostOneReasonFromVerifiedFacilitiesWithoutRepeatingTransportOrDates() {
        var group = group(List.of(room("AVAILABLE", 2)), true);
        String out = PropertyRecommendationText.details(group, result(group, true, 26), true, List.of("Gym", "Study Room"));
        assertTrue(out.contains("推荐理由：如果你平时有健身习惯，可以关注它的健身房配套。"));
        assertEquals(1, occurrences(out, "推荐理由"));
        assertEquals(1, occurrences(out, "地铁约"));
        assertFalse(out.contains("优先级"));
        assertFalse(out.contains("学习空间"));
        assertFalse(PropertyRecommendationText.details(group, result(group, true, 26), true,
                List.of("No gym", "Unknown", "内部优先级1")).contains("推荐理由"));
    }

    @Test
    void zeroMatchesReturnsFriendlyTextInsteadOfRawJsonOrClickableMenuInstructions() {
        var empty = new RoomOfferSearchResult("London", null, List.of(), "UCL", 25,
                LocalDate.parse("2026-12-01"), LocalDate.parse("2026-12-01"), 26, "REFERENCE", 0, 0, 0, List.of(), List.of());
        String out = PropertyRecommendationText.overview(empty, true);
        assertTrue(out.contains("暂时没有找到"));
        assertFalse(out.contains("点击"));
        assertFalse(out.contains("residences"));
        assertFalse(out.contains("找到0个"));
    }

    @Test
    void englishOutputUsesActualModeAndDoesNotInventScarcity() {
        var group = group(List.of(room("LIMITED", null)), true);
        String out = PropertyRecommendationText.overview(result(group, true, null), false);
        assertTrue(out.contains("tube ~15–20 min"));
        assertTrue(out.contains("Available"));
        assertFalse(out.contains("Limited"));
        assertFalse(out.contains("left"));
    }

    private static int occurrences(String text, String needle) { return text.split(java.util.regex.Pattern.quote(needle), -1).length - 1; }

    private static RoomMatch room(String status, Integer quantity) {
        return new RoomMatch(11L, "code", "Bronze Studio", "Studio", LocalDate.parse("2026-11-01"),
                LocalDate.parse("2027-09-01"), LocalDate.parse("2026-12-01"), LocalDate.parse("2027-06-01"), status,
                quantity, null, BigDecimal.valueOf(400), "GBP", BigDecimal.valueOf(10400), null);
    }

    private static ResidenceOfferGroup group(List<RoomMatch> rooms, boolean nearby) {
        return new ResidenceOfferGroup(1L, "source", "Property", "London", "Address", "Station", "1", null, null, null,
                nearby ? List.of(new NearbyPlaceItem("UNIVERSITY", "UCL", "15-20 mins by tube", 15, 20, "TUBE", null)) : List.of(), rooms);
    }

    private static RoomOfferSearchResult result(ResidenceOfferGroup group, boolean nearby, Integer weeks) {
        return new RoomOfferSearchResult("London", null, List.of(), nearby ? "UCL" : null, nearby ? 25 : null,
                LocalDate.parse("2026-12-01"), LocalDate.parse("2026-12-01"), weeks, "REFERENCE", 1, 1, 0, List.of(group), List.of());
    }
}
