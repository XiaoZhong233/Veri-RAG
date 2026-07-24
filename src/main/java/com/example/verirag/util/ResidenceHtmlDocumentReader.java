package com.example.verirag.util;

import com.example.verirag.dto.ResidenceSourceData;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 从 Londonist 地图 HTML 内嵌的 residences JavaScript 对象中提取公寓位置资料，
 * 将每个公寓转换为独立 Markdown，避免通用 HTML 解析器忽略 script 中的核心数据。
 */
@Component
public class ResidenceHtmlDocumentReader {

    private static final Pattern RESIDENCES_START =
            Pattern.compile("\\bconst\\s+residences\\s*=\\s*\\{");
    private static final Pattern ENTRY =
            Pattern.compile("(?m)^\\s{2}([A-Za-z0-9_-]+)\\s*:\\s*\\{");
    private static final Pattern LIST_ITEM = Pattern.compile(
            "(?s)[\"']?name[\"']?\\s*:\\s*\"((?:\\\\.|[^\"\\\\])*)\".*?"
                    + "[\"']?dist[\"']?\\s*:\\s*\"((?:\\\\.|[^\"\\\\])*)\"");
    private static final Pattern QUOTED_VALUE =
            Pattern.compile("\"((?:\\\\.|[^\"\\\\])*)\"");

    public List<Document> read(Path path) {
        List<ResidenceSourceData> residences = readResidenceData(path);
        List<Document> documents = residences.stream()
                .map(this::toDocument)
                .toList();
        List<Document> result = new ArrayList<>(documents.size() + 1);
        result.add(buildPortfolioSummary(residences));
        result.addAll(documents);
        return result;
    }

    /**
     * 读取供 MySQL 地址库使用的结构化公寓数据。
     */
    public List<ResidenceSourceData> readResidenceData(Path path) {
        if (path == null || !Files.isRegularFile(path)) {
            throw new IllegalArgumentException("HTML file does not exist");
        }

        try {
            String html = Files.readString(path, StandardCharsets.UTF_8);
            String residenceObject = extractResidenceObject(html);
            List<ResidenceSourceData> residences = parseResidences(residenceObject);
            if (residences.isEmpty()) {
                throw new IllegalArgumentException(
                        "HTML file does not contain readable residence location data");
            }
            return residences;
        }
        catch (IOException ex) {
            throw new IllegalArgumentException("Failed to read HTML file", ex);
        }
    }

    private String extractResidenceObject(String html) {
        Matcher start = RESIDENCES_START.matcher(html);
        if (!start.find()) {
            throw new IllegalArgumentException(
                    "HTML file does not contain a residences data object");
        }
        int openBrace = html.indexOf('{', start.start());
        int closeBrace = findMatching(html, openBrace, '{', '}');
        return html.substring(openBrace + 1, closeBrace);
    }

    private List<ResidenceSourceData> parseResidences(String source) {
        List<ResidenceSourceData> residences = new ArrayList<>();
        Matcher matcher = ENTRY.matcher(source);
        int searchFrom = 0;
        while (matcher.find(searchFrom)) {
            String residenceId = matcher.group(1);
            int openBrace = source.indexOf('{', matcher.start());
            int closeBrace = findMatching(source, openBrace, '{', '}');
            String block = source.substring(openBrace + 1, closeBrace);
            ResidenceSourceData residence = toResidenceData(residenceId, block);
            if (residence != null) {
                residences.add(residence);
            }
            searchFrom = closeBrace + 1;
        }
        return residences;
    }

    private Document buildPortfolioSummary(List<ResidenceSourceData> residences) {
        Map<String, List<String>> namesByRegion = new LinkedHashMap<>();
        namesByRegion.put("east", new ArrayList<>());
        namesByRegion.put("west", new ArrayList<>());
        namesByRegion.put("north", new ArrayList<>());
        namesByRegion.put("south", new ArrayList<>());

        for (ResidenceSourceData residence : residences) {
            String region = residence.region();
            String name = residence.name();
            if (!name.isBlank()) {
                namesByRegion.computeIfAbsent(region, ignored -> new ArrayList<>()).add(name);
            }
        }

        StringBuilder markdown = new StringBuilder("# Londonist 伦敦公寓位置总览\n\n")
                .append("Londonist 在伦敦共有 **")
                .append(residences.size())
                .append(" 个公寓**。以下是完整公寓名单；该数字来自 HTML 中的全量公寓数据，不是搜索结果数量。\n\n")
                .append("常见问题：你们在伦敦有多少个公寓？答案：")
                .append(residences.size())
                .append(" 个。\n");
        for (Map.Entry<String, List<String>> entry : namesByRegion.entrySet()) {
            if (entry.getValue().isEmpty()) {
                continue;
            }
            markdown.append("\n## ").append(regionLabel(entry.getKey()))
                    .append("（").append(entry.getValue().size()).append(" 个）\n");
            for (String name : entry.getValue()) {
                markdown.append("- ").append(name).append('\n');
            }
        }

        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("contentFormat", "markdown");
        metadata.put("contentType", "residencePortfolioSummary");
        metadata.put("residenceId", "portfolio-summary");
        metadata.put("residenceName", "Londonist 伦敦公寓位置总览");
        metadata.put("residenceCount", residences.size());
        return new Document(markdown.toString().trim(), metadata);
    }

    private ResidenceSourceData toResidenceData(String residenceId, String block) {
        String name = scalar(block, "name");
        if (name.isBlank()) {
            return null;
        }
        String region = scalar(block, "region");
        String zone = scalar(block, "zone");
        String latitude = number(block, "lat");
        String longitude = number(block, "lng");
        String address = scalar(block, "address");
        String station = scalar(block, "station");
        String mapUrl = scalar(block, "mapUrl");
        return new ResidenceSourceData(residenceId, name, region, zone, latitude,
                longitude, address, station, mapUrl, block);
    }

    private Document toDocument(ResidenceSourceData residence) {
        String name = residence.name();
        String region = residence.region();
        String zone = residence.zone();
        String latitude = residence.latitude();
        String longitude = residence.longitude();
        String address = residence.address();
        String station = residence.station();
        String mapUrl = residence.mapUrl();

        // 配套、大学等详情继续从原始数据生成知识库文档；结构化地址库只保存位置字段。
        String block = residence.sourceBlock();
        List<String> amenities = stringArray(block, "amenities");
        List<NameDistance> universities = objectArray(block, "universities");
        List<NameDistance> attractions = objectArray(block, "attractions");
        List<NameDistance> sellingPoints = objectArray(block, "usp");

        StringBuilder markdown = new StringBuilder("## ").append(name).append("\n\n");
        appendField(markdown, "区域", regionLabel(region));
        appendField(markdown, "交通分区", zone);
        appendField(markdown, "地址", address);
        appendField(markdown, "最近车站", station);
        if (!latitude.isBlank() && !longitude.isBlank()) {
            appendField(markdown, "经纬度", latitude + ", " + longitude);
        }
        appendField(markdown, "Google Maps", mapUrl);
        appendStringList(markdown, "配套设施", amenities);
        appendObjectList(markdown, "附近大学", universities);
        appendObjectList(markdown, "周边地点", attractions);
        appendObjectList(markdown, "公寓特色", sellingPoints);

        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("contentFormat", "markdown");
        metadata.put("residenceId", residence.sourceId());
        metadata.put("residenceName", name);
        metadata.put("region", region);
        metadata.put("zone", zone);
        metadata.put("address", address);
        if (!latitude.isBlank()) {
            metadata.put("latitude", latitude);
        }
        if (!longitude.isBlank()) {
            metadata.put("longitude", longitude);
        }
        return new Document(markdown.toString().trim(), metadata);
    }

    private String scalar(String block, String field) {
        Pattern pattern = Pattern.compile("\\b" + Pattern.quote(field)
                + "\\s*:\\s*\"((?:\\\\.|[^\"\\\\])*)\"");
        Matcher matcher = pattern.matcher(block);
        return matcher.find() ? unescape(matcher.group(1)) : "";
    }

    private String number(String block, String field) {
        Pattern pattern = Pattern.compile("\\b" + Pattern.quote(field)
                + "\\s*:\\s*(-?\\d+(?:\\.\\d+)?)");
        Matcher matcher = pattern.matcher(block);
        return matcher.find() ? matcher.group(1) : "";
    }

    private List<String> stringArray(String block, String field) {
        String array = arrayContent(block, field);
        if (array.isBlank()) {
            return List.of();
        }
        List<String> values = new ArrayList<>();
        Matcher matcher = QUOTED_VALUE.matcher(array);
        while (matcher.find()) {
            values.add(unescape(matcher.group(1)));
        }
        return values;
    }

    private List<NameDistance> objectArray(String block, String field) {
        String array = arrayContent(block, field);
        if (array.isBlank()) {
            return List.of();
        }
        List<NameDistance> values = new ArrayList<>();
        Matcher matcher = LIST_ITEM.matcher(array);
        while (matcher.find()) {
            values.add(new NameDistance(
                    unescape(matcher.group(1)), unescape(matcher.group(2))));
        }
        return values;
    }

    private String arrayContent(String block, String field) {
        Pattern pattern = Pattern.compile("\\b" + Pattern.quote(field)
                + "\\s*:\\s*\\[");
        Matcher matcher = pattern.matcher(block);
        if (!matcher.find()) {
            return "";
        }
        int openBracket = block.indexOf('[', matcher.start());
        int closeBracket = findMatching(block, openBracket, '[', ']');
        return block.substring(openBracket + 1, closeBracket);
    }

    private int findMatching(String source, int openIndex, char open, char close) {
        int depth = 0;
        char quote = 0;
        boolean escaped = false;
        for (int index = openIndex; index < source.length(); index++) {
            char current = source.charAt(index);
            if (quote != 0) {
                if (escaped) {
                    escaped = false;
                }
                else if (current == '\\') {
                    escaped = true;
                }
                else if (current == quote) {
                    quote = 0;
                }
                continue;
            }
            if (current == '"' || current == '\'') {
                quote = current;
            }
            else if (current == open) {
                depth++;
            }
            else if (current == close && --depth == 0) {
                return index;
            }
        }
        throw new IllegalArgumentException("Unclosed JavaScript data structure in HTML file");
    }

    private void appendField(StringBuilder markdown, String label, String value) {
        if (!value.isBlank()) {
            markdown.append("- **").append(label).append("**: ").append(value).append('\n');
        }
    }

    private void appendStringList(StringBuilder markdown, String heading, List<String> values) {
        if (values.isEmpty()) {
            return;
        }
        markdown.append("\n### ").append(heading).append('\n');
        for (String value : values) {
            markdown.append("- ").append(value).append('\n');
        }
    }

    private void appendObjectList(StringBuilder markdown, String heading,
                                  List<NameDistance> values) {
        if (values.isEmpty()) {
            return;
        }
        markdown.append("\n### ").append(heading).append('\n');
        for (NameDistance value : values) {
            markdown.append("- ").append(value.name());
            if (!value.distance().isBlank()) {
                markdown.append("：").append(value.distance());
            }
            markdown.append('\n');
        }
    }

    private String regionLabel(String region) {
        return switch (region.toLowerCase(Locale.ROOT)) {
            case "east" -> "东伦敦（East London）";
            case "west" -> "西伦敦（West London）";
            case "north" -> "北伦敦（North London）";
            case "south" -> "南伦敦（South London）";
            default -> region;
        };
    }

    private String unescape(String value) {
        return value.replace("\\\"", "\"")
                .replace("\\'", "'")
                .replace("\\n", "\n")
                .replace("\\\\", "\\");
    }

    private record NameDistance(String name, String distance) {
    }
}
