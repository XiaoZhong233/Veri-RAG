package com.example.verirag.tool;

import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

class PropertyToolSelectorTests {

    private final PropertyToolSelector selector = new PropertyToolSelector(
            new PropertyQueryTools(null, null, null, null, null));

    @Test
    void exposesOnlyBulkSearchForRecommendation() {
        assertThat(toolNames(PropertyQueryIntent.RECOMMEND))
                .containsExactly("search_room_offers");
    }

    @Test
    void exposesOnlyTheToolSelectedByIntent() {
        assertThat(toolNames(PropertyQueryIntent.LIST))
                .containsExactly("list_residences");
        assertThat(toolNames(PropertyQueryIntent.DETAIL))
                .containsExactly("get_residence_details");
        assertThat(toolNames(PropertyQueryIntent.SUMMARY))
                .containsExactly("get_inventory_summary");
        assertThat(toolNames(PropertyQueryIntent.QUOTE))
                .containsExactly("quote_room_offer");
        assertThat(selector.callbacksFor(PropertyQueryIntent.NONE)).isEmpty();
    }

    private String[] toolNames(PropertyQueryIntent intent) {
        return Arrays.stream(selector.callbacksFor(intent))
                .map(callback -> callback.getToolDefinition().name())
                .toArray(String[]::new);
    }
}
