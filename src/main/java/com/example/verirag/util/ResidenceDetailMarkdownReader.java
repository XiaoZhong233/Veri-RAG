package com.example.verirag.util;

import com.example.verirag.dto.ResidenceDetailSourceData;
import com.example.verirag.dto.ResidenceNearbyPlaceSourceData;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class ResidenceDetailMarkdownReader {

    private static final String PORTFOLIO_MARKER = "# Londonist 伦敦学生公寓资料";
    private static final Pattern H2 = Pattern.compile("(?m)^##\\s+(.+?)\\s*$");
    private static final Pattern MINUTES_RANGE = Pattern.compile(
            "(?i)(\\d+)\\s*[–—-]\\s*(\\d+)\\s*(?:minutes?|mins?)");
    private static final Pattern MINUTES = Pattern.compile(
            "(?i)(\\d+)\\s*(?:minutes?|mins?|minute)");
    private static final Pattern MILES = Pattern.compile(
            "(?i)(\\d+(?:\\.\\d+)?)\\s*miles?");
    private static final Pattern TRAVEL_OPTION = Pattern.compile(
            "(?i)(\\d+)\\s*(?:[–—-]\\s*(\\d+)\\s*)?"
                    + "(?:minutes?|mins?)\\s*(?:(?:via|by)\\s*)?"
                    + "(tube|underground|bus|bike|bicycle|cycle|walk|walking|foot|"
                    + "train|thameslink|dlr|public\\s+transport)");

    public List<ResidenceDetailSourceData> read(Path path) {
        if (path == null || !Files.isRegularFile(path)) {
            throw new IllegalArgumentException("Markdown file does not exist");
        }
        try {
            return parse(Files.readString(path, StandardCharsets.UTF_8));
        }
        catch (IOException ex) {
            throw new IllegalArgumentException("Failed to read residence detail Markdown", ex);
        }
    }

    List<ResidenceDetailSourceData> parse(String markdown) {
        if (markdown == null || !markdown.contains(PORTFOLIO_MARKER)) {
            throw new IllegalArgumentException("不是 Londonist 公寓详情 Markdown");
        }
        Matcher matcher = H2.matcher(markdown);
        List<Section> sections = new ArrayList<>();
        while (matcher.find()) {
            sections.add(new Section(matcher.group(1).strip(), matcher.start()));
        }
        List<ResidenceDetailSourceData> result = new ArrayList<>();
        for (int i = 0; i < sections.size(); i++) {
            Section section = sections.get(i);
            if ("公寓索引".equals(section.title())) {
                continue;
            }
            int end = i + 1 < sections.size() ? sections.get(i + 1).start() : markdown.length();
            String text = markdown.substring(section.start(), end).strip();
            String officialUrl = field(text, "来源");
            // “数据说明”等文末章节同样使用二级标题，但不是公寓记录。
            if (officialUrl == null || officialUrl.isBlank()) {
                continue;
            }
            String sourceId = sourceId(officialUrl, text);
            result.add(new ResidenceDetailSourceData(
                    sourceId,
                    section.title(),
                    field(text, "官网公寓 ID"),
                    field(text, "城市"),
                    field(text, "地址"),
                    field(text, "区域"),
                    field(text, "邮编"),
                    field(text, "最近车站"),
                    field(text, "交通线路"),
                    officialUrl,
                    nullIfOfficialMissing(field(text, "页面标签")),
                    splitList(field(text, "设施")),
                    parseNearby(text),
                    text));
        }
        return List.copyOf(result);
    }

    private static List<ResidenceNearbyPlaceSourceData> parseNearby(String section) {
        List<ResidenceNearbyPlaceSourceData> result = new ArrayList<>();
        parseNearbySection(section, "附近学校/大学", "UNIVERSITY", result);
        parseNearbySection(section, "附近地标与生活配套", "LANDMARK", result);
        return List.copyOf(result);
    }

    private static void parseNearbySection(
            String section, String heading, String type,
            List<ResidenceNearbyPlaceSourceData> target) {
        Pattern blockPattern = Pattern.compile(
                "(?ms)^###\\s+" + Pattern.quote(heading) + "\\s*$\\n(.*?)(?=^###\\s+|\\z)");
        Matcher block = blockPattern.matcher(section);
        if (!block.find()) {
            return;
        }
        Matcher line = Pattern.compile("(?m)^-\\s+(.+?)\\s*$").matcher(block.group(1));
        int order = 0;
        while (line.find()) {
            ParsedNearby parsed = splitNearby(line.group(1).strip());
            if (parsed == null) {
                continue;
            }
            for (TravelOption option : travelOptions(parsed.description())) {
                target.add(new ResidenceNearbyPlaceSourceData(
                        type, parsed.name(), option.description(),
                        option.minMinutes(), option.maxMinutes(), option.travelMode(),
                        option.distanceMiles(), order++));
            }
        }
    }

    private static ParsedNearby splitNearby(String value) {
        int colon = value.indexOf(':');
        if (colon > 0) {
            return new ParsedNearby(value.substring(0, colon).strip(),
                    value.substring(colon + 1).strip());
        }
        Matcher dash = Pattern.compile("\\s+[—–]\\s+").matcher(value);
        if (dash.find()) {
            return new ParsedNearby(value.substring(0, dash.start()).strip(),
                    value.substring(dash.end()).strip());
        }
        return null;
    }

    public static TravelMetrics travelMetrics(String description) {
        String value = description == null ? "" : description.strip();
        Matcher option = TRAVEL_OPTION.matcher(value);
        if (option.find()) {
            Integer min = Integer.parseInt(option.group(1));
            Integer max = option.group(2) == null
                    ? min : Integer.parseInt(option.group(2));
            return new TravelMetrics(min, max, travelMode(option.group(3)), null);
        }
        return fallbackTravelMetrics(value);
    }

    public static List<TravelOption> travelOptions(String description) {
        String value = description == null ? "" : description.strip();
        Matcher matcher = TRAVEL_OPTION.matcher(value);
        List<TravelOption> options = new ArrayList<>();
        while (matcher.find()) {
            Integer min = Integer.parseInt(matcher.group(1));
            Integer max = matcher.group(2) == null
                    ? min : Integer.parseInt(matcher.group(2));
            options.add(new TravelOption(
                    matcher.group().strip(), min, max,
                    travelMode(matcher.group(3)), null));
        }
        if (!options.isEmpty()) {
            return List.copyOf(options);
        }
        TravelMetrics fallback = fallbackTravelMetrics(value);
        return List.of(new TravelOption(
                value, fallback.minMinutes(), fallback.maxMinutes(),
                fallback.travelMode(), fallback.distanceMiles()));
    }

    private static TravelMetrics fallbackTravelMetrics(String value) {
        Integer min = null;
        Integer max = null;
        Matcher range = MINUTES_RANGE.matcher(value);
        if (range.find()) {
            min = Integer.parseInt(range.group(1));
            max = Integer.parseInt(range.group(2));
        }
        else {
            Matcher minutes = MINUTES.matcher(value);
            if (minutes.find()) {
                min = Integer.parseInt(minutes.group(1));
                max = min;
            }
        }
        BigDecimal miles = null;
        Matcher distance = MILES.matcher(value);
        if (distance.find()) {
            miles = new BigDecimal(distance.group(1));
        }
        return new TravelMetrics(min, max, travelMode(value), miles);
    }

    private static String travelMode(String value) {
        String normalized = value.toLowerCase(Locale.ROOT);
        if (normalized.contains("walk") || normalized.contains("foot")
                || normalized.contains("next door")
                || normalized.contains("doorstep")) {
            return "WALK";
        }
        if (normalized.contains("bike") || normalized.contains("bicycle")
                || normalized.contains("cycle")) {
            return "BIKE";
        }
        if (normalized.contains("tube") || normalized.contains("underground")) {
            return "TUBE";
        }
        if (normalized.contains("bus")) {
            return "BUS";
        }
        if (normalized.contains("train") || normalized.contains("thameslink")) {
            return "TRAIN";
        }
        if (normalized.contains("dlr")) {
            return "DLR";
        }
        if (normalized.contains("public transport")) {
            return "PUBLIC_TRANSPORT";
        }
        return null;
    }

    private static String field(String text, String label) {
        Matcher matcher = Pattern.compile(
                "(?m)^-\\s*" + Pattern.quote(label) + "：\\s*(.+?)\\s*$")
                .matcher(text);
        return matcher.find() ? matcher.group(1).strip() : null;
    }

    private static String sourceId(String officialUrl, String text) {
        if (officialUrl != null && officialUrl.contains("/")) {
            String withoutQuery = officialUrl.replaceFirst("[?#].*$", "").replaceFirst("/+$", "");
            int slash = withoutQuery.lastIndexOf('/');
            if (slash >= 0 && slash + 1 < withoutQuery.length()) {
                return withoutQuery.substring(slash + 1).toLowerCase(Locale.ROOT);
            }
        }
        String aliases = field(text, "可用名称");
        if (aliases != null && aliases.contains("；")) {
            return aliases.substring(aliases.lastIndexOf('；') + 1).strip()
                    .toLowerCase(Locale.ROOT);
        }
        return null;
    }

    private static List<String> splitList(String value) {
        if (value == null || value.isBlank() || "官网未注明".equals(value)) {
            return List.of();
        }
        return Pattern.compile("[、,，]").splitAsStream(value)
                .map(String::strip)
                .filter(item -> !item.isBlank())
                .distinct()
                .toList();
    }

    private static String nullIfOfficialMissing(String value) {
        return value == null || value.isBlank() || "官网未注明".equals(value) ? null : value;
    }

    private record Section(String title, int start) {
    }

    private record ParsedNearby(String name, String description) {
    }

    public record TravelMetrics(
            Integer minMinutes,
            Integer maxMinutes,
            String travelMode,
            BigDecimal distanceMiles
    ) {
    }

    public record TravelOption(
            String description,
            Integer minMinutes,
            Integer maxMinutes,
            String travelMode,
            BigDecimal distanceMiles
    ) {
    }
}
