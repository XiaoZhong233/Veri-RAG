package com.example.verirag.util;

import com.example.verirag.dto.RoomOfferWorkbookData;
import com.example.verirag.exception.BusinessException;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.DateUtil;
import org.apache.poi.ss.usermodel.FormulaEvaluator;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Component
public class RoomOfferWorkbookReader {

    public static final String INVENTORY_SHEET = "房型库存导入";
    public static final String PRICE_SHEET = "租期价格导入";

    private static final List<String> INVENTORY_HEADERS = List.of(
            "公寓编码", "公寓名称", "房型编码", "房型名称", "Root Type",
            "最早起租日期", "最晚退房日期", "剩余数量", "库存状态", "库存更新时间", "备注");
    private static final List<String> PRICE_HEADERS = List.of(
            "公寓编码", "公寓名称", "房型编码", "房型名称",
            "最早起租日期", "最晚退房日期", "最短租期（周，含）",
            "最长租期（周，含；留空=不限）", "每周价格", "币种", "价格更新时间", "备注");
    private static final List<DateTimeFormatter> DATE_FORMATTERS = List.of(
            DateTimeFormatter.ISO_LOCAL_DATE,
            DateTimeFormatter.ofPattern("yyyy.M.d"),
            DateTimeFormatter.ofPattern("yyyy/M/d"));
    private static final List<DateTimeFormatter> DATE_TIME_FORMATTERS = List.of(
            DateTimeFormatter.ISO_LOCAL_DATE_TIME,
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"),
            DateTimeFormatter.ofPattern("yyyy-M-d H:mm"),
            DateTimeFormatter.ofPattern("yyyy.M.d H:mm"),
            DateTimeFormatter.ofPattern("yyyy/M/d H:mm"));

    public RoomOfferWorkbookData read(InputStream inputStream) {
        try (Workbook workbook = WorkbookFactory.create(inputStream)) {
            DataFormatter formatter = new DataFormatter(Locale.CHINA);
            FormulaEvaluator evaluator = workbook.getCreationHelper().createFormulaEvaluator();
            List<String> errors = new ArrayList<>();
            List<RoomOfferWorkbookData.InventoryRow> inventories =
                    readInventories(workbook, formatter, evaluator, errors);
            List<RoomOfferWorkbookData.PriceRow> prices =
                    readPrices(workbook, formatter, evaluator, errors);
            validateDuplicates(inventories, prices, errors);
            if (!errors.isEmpty()) {
                throw new BusinessException("导入文件校验失败：" + String.join("；", errors.stream()
                        .limit(20).toList()) + (errors.size() > 20 ? "；另有"
                        + (errors.size() - 20) + "个错误" : ""));
            }
            if (inventories.isEmpty()) {
                throw new BusinessException("“房型库存导入”工作表没有可导入的数据");
            }
            if (prices.isEmpty()) {
                throw new BusinessException("“租期价格导入”工作表没有可导入的数据");
            }
            return new RoomOfferWorkbookData(inventories, prices);
        }
        catch (BusinessException ex) {
            throw ex;
        }
        catch (IOException | RuntimeException ex) {
            throw new BusinessException("无法读取 XLSX 文件，请确认文件未损坏且使用了结构化模板");
        }
    }

    private List<RoomOfferWorkbookData.InventoryRow> readInventories(
            Workbook workbook, DataFormatter formatter, FormulaEvaluator evaluator,
            List<String> errors) {
        Sheet sheet = requiredSheet(workbook, INVENTORY_SHEET);
        Map<String, Integer> columns = headerColumns(sheet, INVENTORY_HEADERS, formatter, evaluator);
        List<RoomOfferWorkbookData.InventoryRow> result = new ArrayList<>();
        for (int index = 1; index <= sheet.getLastRowNum(); index++) {
            Row row = sheet.getRow(index);
            if (row == null || blank(row, columns, formatter, evaluator,
                    "公寓编码", "公寓名称", "房型编码", "房型名称")) {
                continue;
            }
            int rowNumber = index + 1;
            try {
                result.add(new RoomOfferWorkbookData.InventoryRow(
                        rowNumber,
                        text(row, columns, "公寓编码", formatter, evaluator),
                        text(row, columns, "公寓名称", formatter, evaluator),
                        requiredText(row, columns, "房型编码", formatter, evaluator),
                        requiredText(row, columns, "房型名称", formatter, evaluator),
                        requiredText(row, columns, "Root Type", formatter, evaluator),
                        requiredDate(row, columns, "最早起租日期", formatter, evaluator),
                        requiredDate(row, columns, "最晚退房日期", formatter, evaluator),
                        optionalInteger(row, columns, "剩余数量", formatter, evaluator),
                        requiredText(row, columns, "库存状态", formatter, evaluator).toUpperCase(Locale.ROOT),
                        requiredDateTime(row, columns, "库存更新时间", formatter, evaluator),
                        nullableText(row, columns, "备注", formatter, evaluator)));
            }
            catch (IllegalArgumentException ex) {
                errors.add(INVENTORY_SHEET + "第" + rowNumber + "行：" + ex.getMessage());
            }
        }
        return result;
    }

    private List<RoomOfferWorkbookData.PriceRow> readPrices(
            Workbook workbook, DataFormatter formatter, FormulaEvaluator evaluator,
            List<String> errors) {
        Sheet sheet = requiredSheet(workbook, PRICE_SHEET);
        Map<String, Integer> columns = headerColumns(sheet, PRICE_HEADERS, formatter, evaluator);
        List<RoomOfferWorkbookData.PriceRow> result = new ArrayList<>();
        for (int index = 1; index <= sheet.getLastRowNum(); index++) {
            Row row = sheet.getRow(index);
            if (row == null || blank(row, columns, formatter, evaluator,
                    "公寓编码", "公寓名称", "房型编码", "房型名称")) {
                continue;
            }
            int rowNumber = index + 1;
            try {
                result.add(new RoomOfferWorkbookData.PriceRow(
                        rowNumber,
                        text(row, columns, "公寓编码", formatter, evaluator),
                        text(row, columns, "公寓名称", formatter, evaluator),
                        requiredText(row, columns, "房型编码", formatter, evaluator),
                        requiredText(row, columns, "房型名称", formatter, evaluator),
                        requiredDate(row, columns, "最早起租日期", formatter, evaluator),
                        requiredDate(row, columns, "最晚退房日期", formatter, evaluator),
                        requiredInteger(row, columns, "最短租期（周，含）", formatter, evaluator),
                        optionalInteger(row, columns, "最长租期（周，含；留空=不限）",
                                formatter, evaluator),
                        requiredDecimal(row, columns, "每周价格", formatter, evaluator),
                        requiredText(row, columns, "币种", formatter, evaluator)
                                .toUpperCase(Locale.ROOT),
                        requiredDateTime(row, columns, "价格更新时间", formatter, evaluator),
                        nullableText(row, columns, "备注", formatter, evaluator)));
            }
            catch (IllegalArgumentException ex) {
                errors.add(PRICE_SHEET + "第" + rowNumber + "行：" + ex.getMessage());
            }
        }
        return result;
    }

    private void validateDuplicates(List<RoomOfferWorkbookData.InventoryRow> inventories,
                                    List<RoomOfferWorkbookData.PriceRow> prices,
                                    List<String> errors) {
        Set<String> inventoryKeys = new HashSet<>();
        for (RoomOfferWorkbookData.InventoryRow row : inventories) {
            String key = identity(row.residenceSourceId(), row.residenceName(), row.roomCode(),
                    row.earliestStartDate(), row.latestEndDate());
            if (!inventoryKeys.add(key)) {
                errors.add(INVENTORY_SHEET + "第" + row.rowNumber() + "行：库存业务标识重复");
            }
        }
        Set<String> priceKeys = new HashSet<>();
        for (RoomOfferWorkbookData.PriceRow row : prices) {
            String key = identity(row.residenceSourceId(), row.residenceName(), row.roomCode(),
                    row.earliestStartDate(), row.latestEndDate()) + "|" + row.minWeeks();
            if (!priceKeys.add(key)) {
                errors.add(PRICE_SHEET + "第" + row.rowNumber() + "行：相同最短周数的价格档位重复");
            }
        }
    }

    private String identity(String residenceCode, String residenceName, String roomCode,
                            LocalDate start, LocalDate end) {
        String residence = !clean(residenceCode).isBlank()
                ? clean(residenceCode).toLowerCase(Locale.ROOT)
                : clean(residenceName).toLowerCase(Locale.ROOT);
        return residence + "|" + clean(roomCode).toLowerCase(Locale.ROOT)
                + "|" + start + "|" + end;
    }

    private Sheet requiredSheet(Workbook workbook, String name) {
        Sheet sheet = workbook.getSheet(name);
        if (sheet == null) {
            throw new BusinessException("缺少工作表：“" + name + "”");
        }
        return sheet;
    }

    private Map<String, Integer> headerColumns(Sheet sheet, List<String> required,
                                                DataFormatter formatter,
                                                FormulaEvaluator evaluator) {
        Row header = sheet.getRow(0);
        if (header == null) {
            throw new BusinessException("工作表“" + sheet.getSheetName() + "”缺少表头");
        }
        Map<String, Integer> columns = new HashMap<>();
        for (Cell cell : header) {
            columns.put(clean(formatter.formatCellValue(cell, evaluator)), cell.getColumnIndex());
        }
        List<String> missing = required.stream().filter(item -> !columns.containsKey(item)).toList();
        if (!missing.isEmpty()) {
            throw new BusinessException("工作表“" + sheet.getSheetName()
                    + "”缺少表头：" + String.join("、", missing));
        }
        return columns;
    }

    private boolean blank(Row row, Map<String, Integer> columns, DataFormatter formatter,
                          FormulaEvaluator evaluator, String... fields) {
        for (String field : fields) {
            if (!text(row, columns, field, formatter, evaluator).isBlank()) {
                return false;
            }
        }
        return true;
    }

    private String requiredText(Row row, Map<String, Integer> columns, String field,
                                DataFormatter formatter, FormulaEvaluator evaluator) {
        String value = text(row, columns, field, formatter, evaluator);
        if (value.isBlank()) {
            throw new IllegalArgumentException(field + "不能为空");
        }
        return value;
    }

    private String nullableText(Row row, Map<String, Integer> columns, String field,
                                DataFormatter formatter, FormulaEvaluator evaluator) {
        String value = text(row, columns, field, formatter, evaluator);
        return value.isBlank() ? null : value;
    }

    private String text(Row row, Map<String, Integer> columns, String field,
                        DataFormatter formatter, FormulaEvaluator evaluator) {
        Cell cell = row.getCell(columns.get(field), Row.MissingCellPolicy.RETURN_BLANK_AS_NULL);
        return cell == null ? "" : clean(formatter.formatCellValue(cell, evaluator));
    }

    private Integer requiredInteger(Row row, Map<String, Integer> columns, String field,
                                    DataFormatter formatter, FormulaEvaluator evaluator) {
        Integer value = optionalInteger(row, columns, field, formatter, evaluator);
        if (value == null) {
            throw new IllegalArgumentException(field + "不能为空");
        }
        return value;
    }

    private Integer optionalInteger(Row row, Map<String, Integer> columns, String field,
                                    DataFormatter formatter, FormulaEvaluator evaluator) {
        Cell cell = row.getCell(columns.get(field), Row.MissingCellPolicy.RETURN_BLANK_AS_NULL);
        if (cell == null || text(row, columns, field, formatter, evaluator).isBlank()) {
            return null;
        }
        BigDecimal value = decimal(cell, formatter, evaluator, field);
        try {
            return value.setScale(0, RoundingMode.UNNECESSARY).intValueExact();
        }
        catch (ArithmeticException ex) {
            throw new IllegalArgumentException(field + "必须是整数");
        }
    }

    private BigDecimal requiredDecimal(Row row, Map<String, Integer> columns, String field,
                                       DataFormatter formatter, FormulaEvaluator evaluator) {
        Cell cell = row.getCell(columns.get(field), Row.MissingCellPolicy.RETURN_BLANK_AS_NULL);
        if (cell == null || text(row, columns, field, formatter, evaluator).isBlank()) {
            throw new IllegalArgumentException(field + "不能为空");
        }
        return decimal(cell, formatter, evaluator, field);
    }

    private BigDecimal decimal(Cell cell, DataFormatter formatter, FormulaEvaluator evaluator,
                               String field) {
        CellType type = cell.getCellType() == CellType.FORMULA
                ? cell.getCachedFormulaResultType() : cell.getCellType();
        if (type == CellType.NUMERIC) {
            return BigDecimal.valueOf(cell.getNumericCellValue());
        }
        try {
            return new BigDecimal(clean(formatter.formatCellValue(cell, evaluator))
                    .replace(",", ""));
        }
        catch (NumberFormatException ex) {
            throw new IllegalArgumentException(field + "必须是数字");
        }
    }

    private LocalDate requiredDate(Row row, Map<String, Integer> columns, String field,
                                   DataFormatter formatter, FormulaEvaluator evaluator) {
        Cell cell = row.getCell(columns.get(field), Row.MissingCellPolicy.RETURN_BLANK_AS_NULL);
        if (cell == null) {
            throw new IllegalArgumentException(field + "不能为空");
        }
        CellType type = cell.getCellType() == CellType.FORMULA
                ? cell.getCachedFormulaResultType() : cell.getCellType();
        if (type == CellType.NUMERIC && DateUtil.isCellDateFormatted(cell)) {
            return cell.getLocalDateTimeCellValue().toLocalDate();
        }
        String value = clean(formatter.formatCellValue(cell, evaluator));
        for (DateTimeFormatter candidate : DATE_FORMATTERS) {
            try {
                return LocalDate.parse(value, candidate);
            }
            catch (DateTimeParseException ignored) {
                // 继续尝试模板允许的其它日期格式。
            }
        }
        throw new IllegalArgumentException(field + "必须是有效日期");
    }

    private LocalDateTime requiredDateTime(Row row, Map<String, Integer> columns, String field,
                                           DataFormatter formatter,
                                           FormulaEvaluator evaluator) {
        Cell cell = row.getCell(columns.get(field), Row.MissingCellPolicy.RETURN_BLANK_AS_NULL);
        if (cell == null) {
            throw new IllegalArgumentException(field + "不能为空");
        }
        CellType type = cell.getCellType() == CellType.FORMULA
                ? cell.getCachedFormulaResultType() : cell.getCellType();
        if (type == CellType.NUMERIC && DateUtil.isCellDateFormatted(cell)) {
            return cell.getLocalDateTimeCellValue();
        }
        String value = clean(formatter.formatCellValue(cell, evaluator));
        for (DateTimeFormatter candidate : DATE_TIME_FORMATTERS) {
            try {
                return LocalDateTime.parse(value, candidate);
            }
            catch (DateTimeParseException ignored) {
                // 继续尝试其它日期时间格式。
            }
        }
        for (DateTimeFormatter candidate : DATE_FORMATTERS) {
            try {
                return LocalDate.parse(value, candidate).atStartOfDay();
            }
            catch (DateTimeParseException ignored) {
                // 继续尝试。
            }
        }
        throw new IllegalArgumentException(field + "必须是有效日期时间");
    }

    private String clean(String value) {
        return value == null ? "" : value.replace('\u00a0', ' ').trim();
    }
}
