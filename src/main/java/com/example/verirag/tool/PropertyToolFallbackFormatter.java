package com.example.verirag.tool;

import com.example.verirag.tool.PropertyQueryTools.InventorySummary;
import com.example.verirag.tool.PropertyQueryTools.NearbyPlaceItem;
import com.example.verirag.tool.PropertyQueryTools.ResidenceDetailItem;
import com.example.verirag.tool.PropertyQueryTools.ResidenceDetailResult;
import com.example.verirag.tool.PropertyQueryTools.ResidenceItem;
import com.example.verirag.tool.PropertyQueryTools.ResidenceListResult;
import com.example.verirag.tool.PropertyQueryTools.ResidenceOfferGroup;
import com.example.verirag.tool.PropertyQueryTools.RoomMatch;
import com.example.verirag.tool.PropertyQueryTools.RoomOfferAvailability;
import com.example.verirag.tool.PropertyQueryTools.RoomOfferSearchResult;

import java.util.List;
import java.util.Objects;
import java.util.StringJoiner;

/**
 * Formats an already completed read-only property Tool result when the model times out while
 * composing the final answer. The formatter intentionally exposes no price fields and makes no
 * booking commitment, so availability data is not lost merely because prose generation failed.
 */
public final class PropertyToolFallbackFormatter {

    private static final String NOTICE_ZH =
            "> 具体价格及可订状态须由 Londonist 顾问最终确认。";
    private static final String NOTICE_EN =
            "> Exact pricing and availability must be confirmed by a Londonist consultant.";

    private PropertyToolFallbackFormatter() {
    }

    public static String format(String question, ToolCallEventContext.Event event) {
        boolean chinese = containsHan(question);
        if (event == null || event.result() == null) {
            return generic(chinese);
        }
        Object result = event.result();
        if (result instanceof RoomOfferSearchResult offers) {
            return formatOffers(offers, chinese);
        }
        if (result instanceof ResidenceDetailResult details) {
            return formatDetails(details, chinese);
        }
        if (result instanceof ResidenceListResult residences) {
            return formatResidences(residences, chinese);
        }
        if (result instanceof RoomOfferAvailability availability) {
            return formatAvailability(availability, chinese);
        }
        if (result instanceof InventorySummary summary) {
            return formatSummary(summary, chinese);
        }
        return generic(chinese);
    }

    private static String formatOffers(RoomOfferSearchResult result, boolean chinese) {
        if (result.residences() == null || result.residences().isEmpty()) {
            return (chinese
                    ? "目前暂时没有找到完全符合这些条件的房源。你可以调整入住日期、租期或房型后重试。\n\n"
                    : "I couldn't find accommodation matching all of those conditions. You can adjust the move-in date, stay length, or room type and try again.\n\n")
                    + notice(chinese);
        }
        StringBuilder answer = new StringBuilder(chinese
                ? "可以的，我已经完成房源核验，找到 " + result.residences().size()
                        + " 个符合条件的公寓：\n\n"
                : "I found " + result.residences().size()
                        + " residences matching your requirements:\n\n");
        answer.append(chinese
                ? "| 公寓 | 位置参考 | 推荐房型 | 可入住时间 | 租期 | 库存 | 匹配说明 |\n"
                        + "|---|---|---|---|---|---|---|\n"
                : "| Residence | Location | Room options | Available dates | Stay | Availability | Match |\n"
                        + "|---|---|---|---|---|---|---|\n");
        for (ResidenceOfferGroup group : result.residences()) {
            List<RoomMatch> rooms = group.rooms() == null ? List.of() : group.rooms();
            answer.append("| ").append(cell(group.residenceName()))
                    .append(" | ").append(cell(location(group)))
                    .append(" | ").append(joinRooms(rooms, RoomMatch::roomName))
                    .append(" | ").append(joinRooms(rooms, room -> dateRange(room, chinese)))
                    .append(" | ").append(result.stayWeeks() == null
                            ? (chinese ? "待确认" : "To confirm")
                            : result.stayWeeks() + (chinese ? "周" : " weeks"))
                    .append(" | ").append(joinRooms(rooms,
                            room -> inventory(room.inventoryStatus(), room.remainingQuantity(), chinese)))
                    .append(" | ").append(chinese
                            ? "符合已提供的筛选条件"
                            : "Matches the supplied filters")
                    .append(" |\n");
        }
        answer.append(chinese
                ? "\n回答整理阶段发生超时，上表由已经完成的房源核验结果直接生成。\n\n"
                : "\nThe response timed out while being composed; the table above was generated directly from the completed availability check.\n\n");
        return answer.append(notice(chinese)).toString();
    }

    private static String formatDetails(ResidenceDetailResult result, boolean chinese) {
        if (result.residences() == null || result.residences().isEmpty()) {
            return (chinese ? "没有找到对应的公寓详情。\n\n"
                    : "I couldn't find details for that residence.\n\n") + notice(chinese);
        }
        StringBuilder answer = new StringBuilder();
        for (ResidenceDetailItem residence : result.residences()) {
            answer.append("## ").append(cell(residence.residenceName())).append("\n\n")
                    .append(chinese ? "- 地址：" : "- Address: ")
                    .append(cell(residence.address())).append("\n")
                    .append(chinese ? "- 最近车站：" : "- Nearest station: ")
                    .append(cell(residence.station())).append("\n");
            if (residence.nearbyPlaces() != null && !residence.nearbyPlaces().isEmpty()) {
                answer.append(chinese ? "- 附近学校及地标：" : "- Nearby universities and landmarks: ")
                        .append(joinNearby(residence.nearbyPlaces())).append("\n");
            }
            if (residence.facilities() != null && !residence.facilities().isEmpty()) {
                answer.append(chinese ? "- 设施：" : "- Facilities: ")
                        .append(String.join("、", residence.facilities())).append("\n");
            }
            answer.append("\n");
        }
        return answer.append(notice(chinese)).toString();
    }

    private static String formatResidences(ResidenceListResult result, boolean chinese) {
        if (result.residences() == null || result.residences().isEmpty()) {
            return (chinese ? "没有找到符合条件的公寓。\n\n"
                    : "I couldn't find any matching residences.\n\n") + notice(chinese);
        }
        StringBuilder answer = new StringBuilder(chinese
                ? "已找到 " + result.count() + " 个公寓：\n\n| 公寓 | 城市 | 地址 | 最近车站 |\n|---|---|---|---|\n"
                : "I found " + result.count() + " residences:\n\n| Residence | City | Address | Nearest station |\n|---|---|---|---|\n");
        for (ResidenceItem residence : result.residences()) {
            answer.append("| ").append(cell(residence.residenceName()))
                    .append(" | ").append(cell(residence.city()))
                    .append(" | ").append(cell(residence.address()))
                    .append(" | ").append(cell(residence.station())).append(" |\n");
        }
        return answer.append("\n").append(notice(chinese)).toString();
    }

    private static String formatAvailability(RoomOfferAvailability result, boolean chinese) {
        String status = result.available()
                ? (chinese ? "可预订" : "Available")
                : (chinese ? "当前不可预订或待确认" : "Unavailable or requires confirmation");
        return (chinese
                ? "房型核验结果：\n\n- 公寓：" + cell(result.residenceName())
                        + "\n- 房型：" + cell(result.roomName()) + "\n- 状态：" + status + "\n\n"
                : "Room check result:\n\n- Residence: " + cell(result.residenceName())
                        + "\n- Room: " + cell(result.roomName()) + "\n- Status: " + status + "\n\n")
                + notice(chinese);
    }

    private static String formatSummary(InventorySummary result, boolean chinese) {
        return (chinese
                ? "当前地址库共有 " + result.residenceCount() + " 个公寓，关联 "
                        + result.roomOfferCount() + " 个房型库存记录。\n\n"
                : "The current property directory contains " + result.residenceCount()
                        + " residences and " + result.roomOfferCount()
                        + " room inventory records.\n\n") + notice(chinese);
    }

    private static String location(ResidenceOfferGroup group) {
        if (group.nearbyMatches() != null && !group.nearbyMatches().isEmpty()) {
            NearbyPlaceItem place = group.nearbyMatches().get(0);
            return String.join(" · ", List.of(
                    nonBlank(place.placeName()), nonBlank(place.travelDescription())).stream()
                    .filter(value -> !value.isBlank()).toList());
        }
        return !nonBlank(group.station()).isBlank() ? group.station() : group.address();
    }

    private static String dateRange(RoomMatch room, boolean chinese) {
        String start = Objects.toString(room.matchedStartDate(),
                Objects.toString(room.earliestStartDate(), "-"));
        String end = Objects.toString(room.matchedEndDate(),
                Objects.toString(room.latestEndDate(), "-"));
        return start + (chinese ? " 至 " : " to ") + end;
    }

    private static String inventory(String status, Integer remaining, boolean chinese) {
        return switch (Objects.toString(status, "UNKNOWN")) {
            case "AVAILABLE" -> chinese ? "可预订" : "Available";
            case "LIMITED" -> remaining == null
                    ? (chinese ? "库存紧张" : "Limited")
                    : (chinese ? "库存紧张（剩余" + remaining + "间）"
                            : "Limited (" + remaining + " remaining)");
            case "SOLD_OUT" -> chinese ? "售罄" : "Sold out";
            default -> chinese ? "待确认" : "To confirm";
        };
    }

    private static String joinNearby(List<NearbyPlaceItem> places) {
        StringJoiner joiner = new StringJoiner("；");
        places.stream().limit(8).forEach(place -> joiner.add(
                cell(place.placeName()) + (nonBlank(place.travelDescription()).isBlank()
                        ? "" : "（" + cell(place.travelDescription()) + "）")));
        return joiner.toString();
    }

    private static <T> String joinRooms(List<T> values, java.util.function.Function<T, String> mapper) {
        if (values == null || values.isEmpty()) {
            return "-";
        }
        return values.stream().map(mapper).map(PropertyToolFallbackFormatter::cell)
                .collect(java.util.stream.Collectors.joining("<br>"));
    }

    private static String cell(String value) {
        String clean = nonBlank(value).replace("|", "\\|")
                .replaceAll("[\\r\\n]+", " ").strip();
        return clean.isBlank() ? "-" : clean;
    }

    private static String nonBlank(String value) {
        return Objects.toString(value, "").strip();
    }

    private static String generic(boolean chinese) {
        return (chinese
                ? "房源查询已经结束，但回答整理超时。本次未返回未经确认的结果，请重试或联系 Londonist 顾问。\n\n"
                : "The property check finished, but the response timed out while being composed. No unconfirmed result has been shown; please retry or contact a Londonist consultant.\n\n")
                + notice(chinese);
    }

    private static String notice(boolean chinese) {
        return chinese ? NOTICE_ZH : NOTICE_EN;
    }

    private static boolean containsHan(String value) {
        return Objects.toString(value, "").codePoints().anyMatch(codePoint ->
                Character.UnicodeScript.of(codePoint) == Character.UnicodeScript.HAN);
    }
}
