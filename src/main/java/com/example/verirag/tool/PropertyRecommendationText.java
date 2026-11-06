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
            if (room.matchedStartDate() != null) out.append("\n").append(zh ? "可入住日期：" : "Move-in date: ").append(room.matchedStartDate());
            out.append("\n").append(zh ? "可租日期范围：" : "Available date range: ")
                    .append(room.earliestStartDate()).append(" — ").append(room.latestEndDate());
            if (room.matchedEndDate() != null) out.append("\n").append(zh ? "退房日期：" : "Move-out date: ").append(room.matchedEndDate());
            if (result.stayWeeks() != null) out.append("\n").append(zh ? "租期：" : "Stay: ").append(result.stayWeeks()).append(zh ? "周" : " weeks");
            out.append("\n").append(zh ? "库存：" : "Availability: ").append(status(room, zh));
        }
        if (group.address() != null) out.append("\n\n").append(zh ? "地址：" : "Address: ").append(group.address());
        if (!group.nearbyMatches().isEmpty()) {
            var place = group.nearbyMatches().getFirst();
            String travel = Objects.toString(place.travelDescription(), "");
            if (travel.isBlank()) travel = Objects.toString(place.travelMode(), "")
                    + (place.maxMinutes() == null ? "" : " " + place.maxMinutes() + (zh ? "分钟" : " min"));
            out.append("\n").append(place.placeName()).append(": ").append(travel);
        }
        String reason = recommendationReason(group, result, zh);
        if (!reason.isBlank()) out.append("\n\n").append(zh ? "推荐理由：" : "Why consider it: ").append(reason);
        appendMissingCriteria(out, result, zh);
        return out.toString();
    }

    /** 仅陈述此次查询返回的客观匹配依据，不读取销售优先级，也不推断未维护的卖点。 */
    static String recommendationReason(ResidenceOfferGroup group, RoomOfferSearchResult result, boolean zh) {
        RoomMatch available = group.rooms().stream()
                .filter(room -> "AVAILABLE".equals(room.inventoryStatus()) || "LIMITED".equals(room.inventoryStatus()))
                .findFirst().orElse(null);
        if (available == null) return "";
        var reasons = new java.util.ArrayList<String>();
        if (result.nearbyPlaceKeyword() != null && !result.nearbyPlaceKeyword().isBlank()
                && !group.nearbyMatches().isEmpty()) {
            var place = group.nearbyMatches().getFirst();
            if (place.placeName() != null && !place.placeName().isBlank()) {
                if (place.maxMinutes() != null && place.travelMode() != null) {
                    String mode = zh ? switch (place.travelMode()) {
                        case "WALK" -> "步行";
                        case "BIKE" -> "骑行";
                        case "TUBE" -> "地铁";
                        case "BUS" -> "公交";
                        case "TRAIN" -> "火车";
                        case "DLR" -> "DLR轻轨";
                        case "PUBLIC_TRANSPORT" -> "公共交通";
                        default -> "";
                    } : place.travelMode();
                    if (!mode.isBlank()) reasons.add(zh
                            ? "前往" + place.placeName() + "，" + mode + "约" + place.maxMinutes() + "分钟"
                            : "Around " + place.maxMinutes() + " minutes to " + place.placeName() + " by " + mode.toLowerCase(java.util.Locale.ROOT));
                } else if (place.travelDescription() != null && !place.travelDescription().isBlank()) {
                    reasons.add(place.placeName() + ": " + place.travelDescription());
                }
            }
        }
        if (result.startDateFrom() != null && available.matchedStartDate() != null) {
            reasons.add(zh ? "有可从" + available.matchedStartDate() + "入住"
                    + (result.stayWeeks() == null ? "的房型" : "、支持" + result.stayWeeks() + "周租期的房型")
                    : "A room can start on " + available.matchedStartDate()
                    + (result.stayWeeks() == null ? "" : " for a " + result.stayWeeks() + "-week stay"));
        }
        return String.join(zh ? "；" : "; ", reasons) + (reasons.isEmpty() ? "" : zh ? "。" : ".");
    }

    private static void appendMissingCriteria(StringBuilder out, RoomOfferSearchResult result, boolean zh) {
        boolean missingDate = result.startDateFrom() == null;
        boolean missingStay = result.stayWeeks() == null;
        if (!missingDate && !missingStay) return;
        String question;
        if (missingDate && missingStay) {
            question = zh ? "你预计什么时候入住、打算住多久呀？告诉小伦同学，我再帮你看看～" :
                    "When would you like to move in, and how long would you like to stay?";
        } else if (missingDate) {
            question = zh ? "你预计什么时候入住呀？告诉小伦同学，我再帮你看看～" :
                    "When would you like to move in? I can help narrow down the options.";
        } else {
            question = zh ? "你打算住多久呀？告诉小伦同学预计租期，我再帮你看看～" :
                    "How long would you like to stay? I can help narrow down the options.";
        }
        out.append("\n\n").append(question);
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
