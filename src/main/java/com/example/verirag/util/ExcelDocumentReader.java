package com.example.verirag.util;

import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.FormulaEvaluator;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 将 XLSX 工作簿转换为适合知识库检索的 Markdown。
 * 每个房型独立成文档，并把分块标题、合并单元格中的公寓信息和动态租期表头带入房型内容。
 */
@Component
public class ExcelDocumentReader {

    private static final Set<String> ROOM_TYPE_HEADERS = Set.of(
            "room type", "roomtype", "房型", "房型名称", "房间类型"
    );
    private static final Set<String> INHERITED_HEADERS = Set.of(
            "city", "城市", "provider", "运营商", "供应商",
            "property name", "property", "公寓", "公寓名称", "项目名称"
    );

    public List<Document> read(Path path) {
        if (path == null || !Files.isRegularFile(path)) {
            throw new IllegalArgumentException("Excel file does not exist");
        }

        try (InputStream input = Files.newInputStream(path);
             Workbook workbook = WorkbookFactory.create(input)) {
            DataFormatter formatter = new DataFormatter(Locale.CHINA);
            FormulaEvaluator evaluator = workbook.getCreationHelper().createFormulaEvaluator();
            List<Document> documents = new ArrayList<>();

            for (int sheetIndex = 0; sheetIndex < workbook.getNumberOfSheets(); sheetIndex++) {
                Sheet sheet = workbook.getSheetAt(sheetIndex);
                if (sheet == null || isSheetEmpty(sheet, formatter, evaluator)) {
                    continue;
                }
                documents.addAll(readSheet(sheet, sheetIndex, formatter, evaluator));
            }
            if (documents.isEmpty()) {
                throw new IllegalArgumentException("Excel file does not contain readable data");
            }
            return documents;
        }
        catch (IOException ex) {
            throw new IllegalArgumentException("Failed to read Excel file", ex);
        }
        catch (RuntimeException ex) {
            if (ex instanceof IllegalArgumentException) {
                throw ex;
            }
            throw new IllegalArgumentException("Invalid or corrupted Excel file", ex);
        }
    }

    private List<Document> readSheet(Sheet sheet, int sheetIndex, DataFormatter formatter,
                                     FormulaEvaluator evaluator) {
        List<Document> documents = new ArrayList<>();
        List<String> headers = List.of();
        Map<Integer, String> inheritedValues = new HashMap<>();
        String sectionTitle = "";

        for (int rowIndex = sheet.getFirstRowNum(); rowIndex <= sheet.getLastRowNum(); rowIndex++) {
            List<String> values = readValues(sheet.getRow(rowIndex), formatter, evaluator);
            List<String> nonBlankValues = values.stream().filter(value -> !value.isBlank()).toList();
            if (nonBlankValues.isEmpty()) {
                continue;
            }

            if (isHeaderRow(values)) {
                headers = values;
                inheritedValues.clear();
                continue;
            }

            if (nonBlankValues.size() == 1) {
                sectionTitle = nonBlankValues.getFirst();
                continue;
            }
            if (headers.isEmpty()) {
                if (!values.isEmpty() && !values.getFirst().isBlank()) {
                    sectionTitle = values.getFirst();
                }
                continue;
            }

            int roomTypeColumn = findRoomTypeColumn(headers);
            if (roomTypeColumn < 0) {
                continue;
            }
            inheritGroupedValues(headers, values, inheritedValues);
            String roomType = valueAt(values, roomTypeColumn);
            if (roomType.isBlank()) {
                continue;
            }

            String propertyName = findPropertyName(headers, values, inheritedValues, sectionTitle);
            String markdown = formatMarkdown(
                    sheet.getSheetName(), propertyName, roomType, headers, values, inheritedValues);
            documents.add(new Document(markdown, metadata(
                    sheet, sheetIndex, rowIndex, propertyName, roomType)));
        }
        return documents;
    }

    private boolean isHeaderRow(List<String> values) {
        return values.stream()
                .map(this::normalizeHeader)
                .anyMatch(ROOM_TYPE_HEADERS::contains);
    }

    private int findRoomTypeColumn(List<String> headers) {
        for (int columnIndex = 0; columnIndex < headers.size(); columnIndex++) {
            if (ROOM_TYPE_HEADERS.contains(normalizeHeader(headers.get(columnIndex)))) {
                return columnIndex;
            }
        }
        return -1;
    }

    private void inheritGroupedValues(List<String> headers, List<String> values,
                                      Map<Integer, String> inheritedValues) {
        for (int columnIndex = 0; columnIndex < headers.size(); columnIndex++) {
            if (!INHERITED_HEADERS.contains(normalizeHeader(headers.get(columnIndex)))) {
                continue;
            }
            String value = valueAt(values, columnIndex);
            if (!value.isBlank()) {
                inheritedValues.put(columnIndex, value);
            }
        }
    }

    private String findPropertyName(List<String> headers, List<String> values,
                                    Map<Integer, String> inheritedValues, String sectionTitle) {
        for (int columnIndex = 0; columnIndex < headers.size(); columnIndex++) {
            String header = normalizeHeader(headers.get(columnIndex));
            if (header.equals("property name") || header.equals("property")
                    || header.equals("公寓") || header.equals("公寓名称")
                    || header.equals("项目名称")) {
                String value = effectiveValue(values, inheritedValues, columnIndex);
                if (!value.isBlank()) {
                    return value;
                }
            }
        }
        return sectionTitle;
    }

    private String formatMarkdown(String sheetName, String propertyName, String roomType,
                                  List<String> headers, List<String> values,
                                  Map<Integer, String> inheritedValues) {
        String heading = propertyName.isBlank() ? sheetName : propertyName;
        StringBuilder markdown = new StringBuilder("## ").append(heading)
                .append("\n\n### ").append(roomType)
                .append("\n\n");

        for (int columnIndex = 0; columnIndex < headers.size(); columnIndex++) {
            String header = headers.get(columnIndex).trim();
            if (header.isBlank()) {
                continue;
            }
            if (ROOM_TYPE_HEADERS.contains(normalizeHeader(header))) {
                continue;
            }
            String value = effectiveValue(values, inheritedValues, columnIndex);
            if (value.isBlank()) {
                continue;
            }
            markdown.append("- **").append(header).append("**: ").append(value).append('\n');
        }
        return markdown.toString().trim();
    }

    private Map<String, Object> metadata(Sheet sheet, int sheetIndex, int rowIndex,
                                         String propertyName, String roomType) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("contentFormat", "markdown");
        metadata.put("sheetName", sheet.getSheetName());
        metadata.put("sheetIndex", sheetIndex);
        metadata.put("rowNumber", rowIndex + 1);
        metadata.put("propertyName", propertyName);
        metadata.put("roomType", roomType);
        return metadata;
    }

    private String effectiveValue(List<String> values, Map<Integer, String> inheritedValues,
                                  int columnIndex) {
        String value = valueAt(values, columnIndex);
        return value.isBlank() ? inheritedValues.getOrDefault(columnIndex, "") : value;
    }

    private String valueAt(List<String> values, int columnIndex) {
        return columnIndex >= 0 && columnIndex < values.size() ? values.get(columnIndex).trim() : "";
    }

    private String normalizeHeader(String value) {
        return value == null ? "" : value
                .replace('\u00a0', ' ')
                .trim()
                .replaceAll("\\s+", " ")
                .toLowerCase(Locale.ROOT);
    }

    private boolean isSheetEmpty(Sheet sheet, DataFormatter formatter, FormulaEvaluator evaluator) {
        for (Row row : sheet) {
            if (readValues(row, formatter, evaluator).stream().anyMatch(value -> !value.isBlank())) {
                return false;
            }
        }
        return true;
    }

    private List<String> readValues(Row row, DataFormatter formatter, FormulaEvaluator evaluator) {
        if (row == null || row.getLastCellNum() < 0) {
            return List.of();
        }
        List<String> values = new ArrayList<>(row.getLastCellNum());
        for (int columnIndex = 0; columnIndex < row.getLastCellNum(); columnIndex++) {
            Cell cell = row.getCell(columnIndex, Row.MissingCellPolicy.RETURN_BLANK_AS_NULL);
            String value = cell == null ? "" : formatter.formatCellValue(cell, evaluator).trim();
            values.add(value);
        }
        return values;
    }
}
