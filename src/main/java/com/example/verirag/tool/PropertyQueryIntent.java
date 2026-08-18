package com.example.verirag.tool;

/** 房源问题的确定性 Tool 路由意图。 */
public enum PropertyQueryIntent {
    NONE(null),
    ACKNOWLEDGE(null),
    CLARIFY(null),
    GUIDANCE(null),
    RESTRICTED(null),
    RECOMMEND("search_room_offers"),
    QUOTE("check_room_offer_availability"),
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
        return toolName != null;
    }

    /** 房源领域请求；其中 ACKNOWLEDGE/CLARIFY/GUIDANCE/RESTRICTED 不检索 RAG，也不调用 Tool。 */
    public boolean propertyHandled() {
        return this != NONE;
    }
}
