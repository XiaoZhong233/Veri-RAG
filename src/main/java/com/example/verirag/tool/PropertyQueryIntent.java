package com.example.verirag.tool;

/** 房源问题的确定性 Tool 路由意图。 */
public enum PropertyQueryIntent {
    NONE(null),
    RECOMMEND("search_room_offers"),
    QUOTE("quote_room_offer"),
    DETAIL("get_residence_details"),
    LIST("list_residences"),
    SUMMARY("get_inventory_summary");

    private final String toolName;

    PropertyQueryIntent(String toolName) {
        this.toolName = toolName;
    }

    public String toolName() {
        return toolName;
    }

    public boolean structured() {
        return this != NONE;
    }
}
