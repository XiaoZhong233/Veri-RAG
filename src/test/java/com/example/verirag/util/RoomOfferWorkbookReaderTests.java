package com.example.verirag.util;

import com.example.verirag.dto.RoomOfferWorkbookData;
import com.example.verirag.exception.BusinessException;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RoomOfferWorkbookReaderTests {

    private final RoomOfferWorkbookReader reader = new RoomOfferWorkbookReader();

    @Test
    void readsStructuredInventoryAndPriceTier() throws Exception {
        byte[] workbook = workbook(true);

        RoomOfferWorkbookData result = reader.read(new ByteArrayInputStream(workbook));

        assertThat(result.inventories()).hasSize(1);
        assertThat(result.inventories().getFirst().residenceSourceId()).isEqualTo("aldgate");
        assertThat(result.inventories().getFirst().earliestStartDate())
                .isEqualTo(LocalDate.of(2026, 9, 5));
        assertThat(result.prices()).hasSize(1);
        assertThat(result.prices().getFirst().minWeeks()).isEqualTo(40);
        assertThat(result.prices().getFirst().maxWeeks()).isNull();
        assertThat(result.prices().getFirst().weeklyPrice()).isEqualByComparingTo("410");
    }

    @Test
    void rejectsWorkbookWithoutPriceSheet() throws Exception {
        byte[] workbook = workbook(false);

        assertThatThrownBy(() -> reader.read(new ByteArrayInputStream(workbook)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("租期价格导入");
    }

    private byte[] workbook(boolean includePrices) throws Exception {
        try (XSSFWorkbook workbook = new XSSFWorkbook();
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            Sheet inventory = workbook.createSheet(RoomOfferWorkbookReader.INVENTORY_SHEET);
            write(inventory.createRow(0), new Object[]{
                    "公寓编码", "公寓名称", "房型编码", "房型名称", "Root Type",
                    "最早起租日期", "最晚退房日期", "剩余数量", "库存状态", "库存更新时间", "备注"});
            write(inventory.createRow(1), new Object[]{
                    "aldgate", "Aldgate Residence", "ALD-CLASSIC", "Classic Ensuite", "Ensuite",
                    "2026-09-05", "2027-08-28", 6, "AVAILABLE", "2026-07-24 12:00", "测试"});
            if (includePrices) {
                Sheet prices = workbook.createSheet(RoomOfferWorkbookReader.PRICE_SHEET);
                write(prices.createRow(0), new Object[]{
                        "公寓编码", "公寓名称", "房型编码", "房型名称",
                        "最早起租日期", "最晚退房日期", "最短租期（周，含）",
                        "最长租期（周，含；留空=不限）", "每周价格", "币种", "价格更新时间", "备注"});
                write(prices.createRow(1), new Object[]{
                        "aldgate", "Aldgate Residence", "ALD-CLASSIC", "Classic Ensuite",
                        "2026-09-05", "2027-08-28", 40, null, 410, "GBP",
                        "2026-07-24 12:00", "40周以上"});
            }
            workbook.write(output);
            return output.toByteArray();
        }
    }

    private void write(Row row, Object[] values) {
        for (int index = 0; index < values.length; index++) {
            Object value = values[index];
            if (value == null) {
                continue;
            }
            if (value instanceof Number number) {
                row.createCell(index).setCellValue(number.doubleValue());
            }
            else {
                row.createCell(index).setCellValue(value.toString());
            }
        }
    }
}
