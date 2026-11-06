package com.example.verirag.tool;

import com.example.verirag.tool.PropertyQueryTools.*;
import java.math.BigDecimal;
import java.util.Objects;

/** 微信的简短推荐和按需详情共用同一份确定性展示，历史与实际发送保持一致。 */
public final class PropertyRecommendationText {
    private PropertyRecommendationText() {}

    public static String overview(RoomOfferSearchResult result, boolean zh) {
        var groups = result.residences().stream().limit(4).toList();
        if (groups.isEmpty()) {
            StringBuilder empty = new StringBuilder(zh ? "目前暂时没有找到符合这些条件的公寓。" : "I haven't found any properties that fit these criteria.");
            if (result.startDateFrom() == null || result.stayWeeks() == null) appendMissingCriteria(empty, result, zh);
            else empty.append(zh ? "\n\n要不要调整一下入住时间、预算或位置？" : "\n\nWould you like to adjust the dates, budget or location?");
            return empty.toString();
        }
        StringBuilder out = new StringBuilder(zh ? "找到" + groups.size() + "个匹配的公寓：" :
                "Found " + groups.size() + " matching properties:");
        for (int i = 0; i < groups.size(); i++) {
            var group = groups.get(i);
            out.append("\n\n").append(i + 1).append(". 🏠 ").append(group.residenceName());
            if (!group.rooms().isEmpty()) {
                var room = group.rooms().getFirst();
                out.append("\n").append(room.rootType()).append("｜").append(price(room, zh))
                        .append("｜").append(status(room, zh, false));
            }
        }
        return out.toString();
    }

    /** 补充条件的追问留在菜单里一次，不占用简短概览，也不在各公寓详情里重复。 */
    public static String menuHeading(RoomOfferSearchResult result, boolean zh) {
        StringBuilder heading = new StringBuilder();
        appendMissingCriteria(heading, result, zh);
        String choose = zh ? "👇 点击公寓查看房型、日期及库存，也可以直接回复公寓名称：" :
                "👇 Choose a property for details, or reply with its name:";
        return heading.isEmpty() ? choose : heading.toString().strip() + "\n" + choose;
    }

    public static String details(ResidenceOfferGroup group, RoomOfferSearchResult result, boolean zh) {
        StringBuilder out = new StringBuilder("🏠 ").append(group.residenceName());
        boolean commonDates = !group.rooms().isEmpty() && group.rooms().stream()
                .map(room -> dates(room, zh)).distinct().count() == 1;
        for (var room : group.rooms()) {
            out.append("\n\n").append(zh ? "🔑 房型：" : "🔑 Room: ").append(room.roomName())
                    .append("\n💷 ").append(price(room, zh));
            if (room.estimatedTotalPrice() != null) {
                out.append(zh ? "；参考总价：" : "; reference total: ")
                        .append(money(room.estimatedTotalPrice(), room.currency()));
            }
            out.append("\n").append(zh ? "📌 库存：" : "📌 Availability: ").append(status(room, zh));
            if (!commonDates && !dates(room, zh).isBlank()) out.append("\n").append(dates(room, zh));
        }
        if (commonDates && !dates(group.rooms().getFirst(), zh).isBlank()) out.append("\n\n").append(dates(group.rooms().getFirst(), zh));
        if (result.stayWeeks() != null) out.append("\n").append(zh ? "⏳ 租期：" : "⏳ Stay: ").append(result.stayWeeks()).append(zh ? "周" : " weeks");
        if (group.address() != null) out.append("\n\n").append(zh ? "📍 地址：" : "📍 Address: ").append(group.address());
        if (!group.nearbyMatches().isEmpty()) {
            String travel = commute(group.nearbyMatches().getFirst(), zh);
            if (!travel.isBlank()) out.append("\n").append(travel);
        }
        // 只在本轮概览中追问；浏览不同公寓时不重复催问租期。
        return out.toString();
    }

    private static String dates(RoomMatch room, boolean zh) {
        if (room.matchedStartDate() != null && room.matchedEndDate() != null) return zh
                ? "📅 入住：" + room.matchedStartDate() + "｜退房：" + room.matchedEndDate()
                : "📅 Move in: " + room.matchedStartDate() + " | Move out: " + room.matchedEndDate();
        if (room.matchedStartDate() != null) return (zh ? "📅 可从" : "📅 Move in from ") + room.matchedStartDate()
                + (zh ? "入住" : "") + (room.latestEndDate() == null ? "" : (zh ? "，最晚" : "; latest move-out ")
                + room.latestEndDate() + (zh ? "退房" : ""));
        if (room.earliestStartDate() == null || room.latestEndDate() == null) return "";
        return (zh ? "📅 可租时间：" : "📅 Available dates: ") + room.earliestStartDate() + " — " + room.latestEndDate();
    }

    private static String commute(NearbyPlaceItem place, boolean zh) {
        String mode = switch (Objects.toString(place.travelMode(), "")) {
            case "WALK" -> zh ? "步行" : "walking";
            case "BIKE" -> zh ? "骑行" : "cycling";
            case "TUBE" -> zh ? "地铁" : "tube";
            case "BUS" -> zh ? "公交" : "bus";
            case "TRAIN" -> zh ? "火车" : "train";
            case "DLR" -> zh ? "DLR轻轨" : "DLR";
            case "PUBLIC_TRANSPORT" -> zh ? "公共交通" : "public transport";
            default -> "";
        };
        String description = Objects.toString(place.travelDescription(), "");
        if (!mode.isBlank() && place.maxMinutes() != null && place.maxMinutes() > 0) {
            String minutes = place.minMinutes() != null && place.minMinutes() > 0 && place.minMinutes() < place.maxMinutes()
                    ? place.minMinutes() + "–" + place.maxMinutes() : place.maxMinutes().toString();
            description = mode + (zh ? "约" : " ~") + minutes + (zh ? "分钟" : " min");
        }
        if (description.isBlank()) return "";
        return "🧭 " + (Objects.toString(place.placeName(), "").isBlank() ? "" : place.placeName() + "：") + description;
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
        return status(room, zh, true);
    }

    private static String status(RoomMatch room, boolean zh, boolean detailed) {
        String state = Objects.toString(room.inventoryStatus(), "UNKNOWN");
        if ("SOLD_OUT".equals(state)) return zh ? "售罄" : "Sold out";
        if (!java.util.List.of("AVAILABLE", "LIMITED").contains(state)
                || (room.remainingQuantity() != null && room.remainingQuantity() <= 0)) return zh ? "待确认" : "To confirm";
        String quantity = !detailed || room.remainingQuantity() == null ? "" : (zh ? "（剩余" : " (") + room.remainingQuantity() + (zh ? "间）" : " left)");
        return (detailed && "LIMITED".equals(state) && room.remainingQuantity() != null
                ? (zh ? "库存紧张" : "Limited") : (zh ? "可预订" : "Available")) + quantity;
    }

    public static String notice(boolean zh) {
        return zh ? "参考价格及可订状态须由 Londonist 顾问最终确认。" :
                "Reference pricing and availability must be confirmed by a Londonist consultant.";
    }

}
