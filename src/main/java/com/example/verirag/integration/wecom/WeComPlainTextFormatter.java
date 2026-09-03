package com.example.verirag.integration.wecom;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/** 将模型偶尔返回的 Markdown 降级为适合微信客服展示的纯文本。 */
final class WeComPlainTextFormatter {

    private static final Pattern TABLE_SEPARATOR = Pattern.compile(
            "^\\s*\\|?\\s*:?-{3,}:?\\s*(?:\\|\\s*:?-{3,}:?\\s*)+\\|?\\s*$");
    private static final Pattern HORIZONTAL_RULE = Pattern.compile("^\\s*([-*_])(?:\\s*\\1){2,}\\s*$");

    private WeComPlainTextFormatter() {
    }

    static String format(String markdown) {
        if (markdown == null || markdown.isBlank()) {
            return "";
        }
        String[] lines = markdown.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1);
        StringBuilder result = new StringBuilder(markdown.length());
        boolean fencedCode = false;
        for (int index = 0; index < lines.length;) {
            String line = lines[index];
            if (line.stripLeading().startsWith("```")) {
                fencedCode = !fencedCode;
                index++;
                continue;
            }
            if (!fencedCode && index + 1 < lines.length
                    && line.contains("|") && TABLE_SEPARATOR.matcher(lines[index + 1]).matches()) {
                index = appendTable(lines, index, result);
                continue;
            }
            if (!fencedCode && HORIZONTAL_RULE.matcher(line).matches()) {
                index++;
                continue;
            }
            if (!fencedCode) {
                line = line.replaceFirst("^\\s*#{1,6}\\s+", "")
                        .replaceFirst("^\\s*>\\s?", "")
                        .replaceFirst("^\\s*[-*+]\\s+", "• ");
                line = formatInline(line);
            }
            result.append(line.stripTrailing()).append('\n');
            index++;
        }
        return collapseBlankLines(result.toString()).strip();
    }

    private static int appendTable(String[] lines, int headerIndex, StringBuilder result) {
        List<String> headers = parseRow(lines[headerIndex]);
        int index = headerIndex + 2;
        int rowNumber = 1;
        while (index < lines.length && lines[index].contains("|") && !lines[index].isBlank()) {
            List<String> values = parseRow(lines[index]);
            if (values.stream().anyMatch(value -> !value.isBlank())) {
                appendTableRow(result, headers, values, rowNumber++);
            }
            index++;
        }
        return index;
    }

    private static void appendTableRow(
            StringBuilder result, List<String> headers, List<String> values, int rowNumber) {
        String title = values.isEmpty() ? "" : values.getFirst();
        result.append(rowNumber).append(". ").append(title.isBlank() ? "记录" : title).append('\n');
        int columns = Math.max(headers.size(), values.size());
        for (int column = 1; column < columns; column++) {
            String value = column < values.size() ? values.get(column) : "";
            if (value.isBlank()) {
                continue;
            }
            String header = column < headers.size() ? headers.get(column) : "信息";
            result.append("   ").append(header.isBlank() ? "信息" : header)
                    .append('：').append(value).append('\n');
        }
        result.append('\n');
    }

    private static List<String> parseRow(String line) {
        String row = line.strip();
        if (row.startsWith("|")) {
            row = row.substring(1);
        }
        if (row.endsWith("|")) {
            row = row.substring(0, row.length() - 1);
        }
        String[] cells = row.split("(?<!\\\\)\\|", -1);
        List<String> result = new ArrayList<>(cells.length);
        for (String cell : cells) {
            result.add(formatInline(cell.replace("\\|", "|").strip()));
        }
        return result;
    }

    private static String formatInline(String value) {
        return value
                .replaceAll("(?i)<br\\s*/?>", "、")
                .replaceAll("!\\[([^]]*)]\\(([^)]+)\\)", "$1（$2）")
                .replaceAll("\\[([^]]+)]\\(([^)]+)\\)", "$1：$2")
                .replaceAll("\\*\\*(.+?)\\*\\*", "$1")
                .replaceAll("__(.+?)__", "$1")
                .replaceAll("~~(.+?)~~", "$1")
                .replaceAll("`([^`]+)`", "$1");
    }

    private static String collapseBlankLines(String value) {
        return value.replaceAll("\n[ \\t]*\n(?:[ \\t]*\n)+", "\n\n");
    }
}
