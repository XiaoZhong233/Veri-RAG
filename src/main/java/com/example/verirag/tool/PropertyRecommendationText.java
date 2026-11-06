package com.example.verirag.tool;

import com.example.verirag.tool.PropertyQueryTools.*;
import java.math.BigDecimal;
import java.util.Objects;

/** 微信的简短推荐和按需详情共用同一份确定性展示，历史与实际发送保持一致。 */
public final class PropertyRecommendationText {
    private PropertyRecommendationText() {}

    public static String overview(RoomOfferSearchResult result, boolean zh) {
        var groups = result.residences().stream().limit(4).toList();
        StringBuilder out = new StringBuilder(zh ? "找到" + groups.size() + "个匹配的公寓：" :
                "Found " + groups.size() + " matching properties:");
        for (int i = 0; i < groups.size(); i++) {
            var group = groups.get(i);
            out.append("\n\n").append(i + 1).append(". ").append(group.residenceName());
            if (!group.rooms().isEmpty()) {
                var room = group.rooms().getFirst();
                out.append("\n").append(room.rootType()).append("｜").append(price(room, zh))
                        .append("｜").append(status(room, zh));
            }
        }
        out.append("\n\n").append(zh ? "点击下方菜单查看详情，也可以直接告诉我新的找房条件。" :
                "Choose below for details, or send your new search criteria.");
        appendMissingCriteria(out, result, zh);
        return out.append("\n").append(notice(zh)).toString();
    }

    public static String details(ResidenceOfferGroup group, RoomOfferSearchResult result, boolean zh) {
        StringBuilder out = new StringBuilder(group.residenceName());
        for (var room : group.rooms()) {
            out.append("\n\n").append(zh ? "房型：" : "Room: ").append(room.roomName())
                    .append("\n").append(price(room, zh));
            if (room.estimatedTotalPrice() != null) {
                out.append(zh ? "；参考总价：" : "; reference total: ")
                        .append(money(room.estimatedTotalPrice(), room.currency()));
            }
            if (room.matchedStartDate() != null) out.append("\n").append(zh ? "匹配起租日：" : "Matched start: ").append(room.matchedStartDate());
            out.append("\n").append(zh ? "可租日期范围：" : "Available date range: ")
                    .append(room.earliestStartDate()).append(" — ").append(room.latestEndDate());
            if (room.matchedEndDate() != null) out.append("\n").append(zh ? "匹配退房日：" : "Matched end: ").append(room.matchedEndDate());
            if (result.stayWeeks() != null) out.append("\n").append(zh ? "租期：" : "Stay: ").append(result.stayWeeks()).append(zh ? "周" : " weeks");
            out.append("\n").append(zh ? "库存：" : "Availability: ").append(status(room, zh));
        }
        if (group.address() != null) out.append("\n\n").append(zh ? "地址：" : "Address: ").append(group.address());
        if (!group.nearbyMatches().isEmpty()) {
            var place = group.nearbyMatches().getFirst();
            String travel = Objects.toString(place.travelDescription(), "");
            if (travel.isBlank()) travel = Objects.toString(place.travelMode(), "")
                    + (place.maxMinutes() == null ? "" : " " + place.maxMinutes() + (zh ? "分钟" : " min"));
            out.append("\n").append(place.placeName()).append(": ").append(travel)
                    .append(zh ? "（业务资料，非实时路线）" : " (business data, not live routing)");
        }
        appendMissingCriteria(out, result, zh);
        out.append("\n\n").append(zh ? "已按原查询条件重新核验；库存为业务数据快照。" :
                "Rechecked against your original criteria; availability is a business-data snapshot.");
        return out.toString();
    }

    private static void appendMissingCriteria(StringBuilder out, RoomOfferSearchResult result, boolean zh) {
        if (result.startDateFrom() == null) out.append("\n").append(zh ? "尚未指定起租日，入住日期待核验。" : "Start date not specified; dates need checking.");
        if (result.stayWeeks() == null) out.append("\n").append(zh ? "尚未指定租期，适用租期及价格待核验。" : "Stay length not specified; term and pricing need checking.");
    }

    private static String price(RoomMatch room, boolean zh) {
        return room.referenceWeeklyPrice() == null ? (zh ? "参考价待确认" : "Reference price to confirm") :
                (zh ? "参考 " : "Reference ") + money(room.referenceWeeklyPrice(), room.currency())
                        + (zh ? "/周" : "/week") + (room.estimatedTotalPrice() == null ? (zh ? "起" : " from") : "");
    }

    private static String money(BigDecimal value, String currency) {
        return ("GBP".equals(currency) ? "£" : Objects.toString(currency, "") + " ") + value.stripTrailingZeros().toPlainString();
    }

    private static String status(RoomMatch room, boolean zh) {
        return switch (Objects.toString(room.inventoryStatus(), "UNKNOWN")) {
            case "AVAILABLE" -> zh ? "可预订" : "Available";
            case "LIMITED" -> (zh ? "库存紧张" : "Limited") + (room.remainingQuantity() == null ? "" :
                    (zh ? "（剩" : " (") + room.remainingQuantity() + (zh ? "间）" : " left)"));
            case "SOLD_OUT" -> zh ? "售罄" : "Sold out";
            default -> zh ? "待确认" : "To confirm";
        };
    }

    public static String notice(boolean zh) {
        return zh ? "参考价格及可订状态须由 Londonist 顾问最终确认。" :
                "Reference pricing and availability must be confirmed by a Londonist consultant.";
    }

}
