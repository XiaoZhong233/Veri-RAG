package com.example.verirag.tool;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PropertyToolFallbackFormatterTests {

    @Test
    void formatsCompletedEnglishOfferSearchWithReferencePrices() {
        PropertyQueryTools.RoomMatch room = new PropertyQueryTools.RoomMatch(
                11L, "classic-studio", "Classic Studio", "Studio",
                LocalDate.of(2026, 9, 1), LocalDate.of(2027, 8, 31),
                LocalDate.of(2026, 9, 1), LocalDate.of(2027, 3, 2),
                "AVAILABLE", 4, null,
                new java.math.BigDecimal("430"), "GBP",
                new java.math.BigDecimal("11180"), null);
        PropertyQueryTools.ResidenceOfferGroup residence =
                new PropertyQueryTools.ResidenceOfferGroup(
                        1L, "islington", "Islington Residence", "London",
                        "Market Road", "Caledonian Road", "Zone 2",
                        null, null, null,
                        List.of(new PropertyQueryTools.NearbyPlaceItem(
                                "UNIVERSITY", "UCL", "18 minutes by tube",
                                18, 18, "TUBE", null)),
                        List.of(room));
        PropertyQueryTools.RoomOfferSearchResult result =
                new PropertyQueryTools.RoomOfferSearchResult(
                        "London", null, List.of(), "UCL", 25,
                        LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 30),
                        26, "REFERENCE_PRICE_AVAILABLE",
                        1, 1, 0, List.of(residence), List.of());
        ToolCallEventContext.Event event = new ToolCallEventContext.Event(
                ToolCallEventContext.Phase.COMPLETED, "search_room_offers", result);

        String answer = PropertyToolFallbackFormatter.format(
                "Find accommodation near UCL for 26 weeks", event);

        assertThat(answer)
                .contains("| Residence | Location | Room options")
                .contains("| Islington Residence | UCL · 18 minutes by tube | Classic Studio")
                .contains("Reference price", "£430/week", "estimated total £11180")
                .contains("Reference pricing and availability must be confirmed by a Londonist consultant.");
    }

    @Test
    void formatsNoMatchWithoutInventingResidence() {
        PropertyQueryTools.RoomOfferSearchResult result =
                new PropertyQueryTools.RoomOfferSearchResult(
                        "London", null, List.of(), "KCL", 25,
                        LocalDate.of(2026, 9, 20), LocalDate.of(2026, 9, 20),
                        17, "REFERENCE_PRICE_AVAILABLE",
                        0, 0, 0, List.of(), List.of());
        ToolCallEventContext.Event event = new ToolCallEventContext.Event(
                ToolCallEventContext.Phase.COMPLETED, "search_room_offers", result);

        String answer = PropertyToolFallbackFormatter.format(
                "KCL附近9月20日起租17周", event);

        assertThat(answer)
                .contains("目前暂时没有找到完全符合这些条件的房源")
                .doesNotContain("| 公寓 |")
                .endsWith("参考价格及可订状态须由 Londonist 顾问最终确认。");
    }
}
