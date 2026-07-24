package com.example.verirag.tool;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class PropertyQueryToolsIntegrationTests {

    @Autowired
    private PropertyQueryTools tools;

    @Test
    void readsCurrentLondonInventoryWithoutRag() {
        PropertyQueryTools.InventorySummary summary =
                tools.getInventorySummary("London");
        assertThat(summary.residenceCount()).isPositive();
        assertThat(summary.roomOfferCount()).isPositive();

        PropertyQueryTools.RoomOfferSearchResult result = tools.searchRoomOffers(
                "London", null, null, "2026-09-01", "2026-09-30",
                26, null, null, true, 8);

        assertThat(result.matchedResidenceCount()).isGreaterThanOrEqualTo(4);
        assertThat(result.residences())
                .extracting(PropertyQueryTools.ResidenceOfferGroup::residenceId)
                .doesNotHaveDuplicates();
        assertThat(result.residences())
                .allSatisfy(group -> assertThat(group.rooms()).isNotEmpty());
    }
}
