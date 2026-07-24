package com.example.verirag.util;

import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.document.Document;

import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ExcelDocumentReaderTests {

    @TempDir
    Path tempDir;

    private final ExcelDocumentReader reader = new ExcelDocumentReader();

    @Test
    void convertsBlockStylePriceListToRoomMarkdown() throws Exception {
        Path file = tempDir.resolve("room-types.xlsx");
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            var sheet = workbook.createSheet("酒店房型");
            sheet.createRow(0).createCell(0)
                    .setCellValue("Chapter London Bridge（2026.9.12--2027.9.3)");
            var header = sheet.createRow(1);
            header.createCell(0).setCellValue("Room Type");
            header.createCell(1).setCellValue("16-19 weeks");
            header.createCell(2).setCellValue("20-39 Weeks");
            header.createCell(3).setCellValue("40-51 Weeks");
            header.createCell(4).setCellValue("Room Availability");
            header.createCell(5).setCellValue("Note");
            var room = sheet.createRow(2);
            room.createCell(0).setCellValue("Bronze Studio River View Upper Level");
            room.createCell(1).setCellValue(615);
            room.createCell(2).setCellValue(595);
            room.createCell(3).setCellFormula("500+65");
            room.createCell(4).setCellValue("最后4间");
            room.createCell(5).setCellValue("19-22层");
            try (OutputStream output = Files.newOutputStream(file)) {
                workbook.write(output);
            }
        }

        List<Document> documents = reader.read(file);

        assertThat(documents).hasSize(1);
        Document room = documents.getFirst();
        assertThat(room.getText())
                .startsWith("## Chapter London Bridge（2026.9.12--2027.9.3)")
                .contains("### Bronze Studio River View Upper Level",
                        "- **16-19 weeks**: 615",
                        "- **20-39 Weeks**: 595",
                        "- **40-51 Weeks**: 565",
                        "- **Room Availability**: 最后4间",
                        "- **Note**: 19-22层");
        assertThat(room.getMetadata())
                .containsEntry("contentFormat", "markdown")
                .containsEntry("sheetName", "酒店房型")
                .containsEntry("rowNumber", 3)
                .containsEntry("propertyName",
                        "Chapter London Bridge（2026.9.12--2027.9.3)")
                .containsEntry("roomType", "Bronze Studio River View Upper Level");
    }

    @Test
    void fillsMergedPropertyFieldsInStandardPriceList() throws Exception {
        Path file = tempDir.resolve("rooms.xlsx");
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            workbook.createSheet("空表");
            var sheet = workbook.createSheet("China Price List");
            var header = sheet.createRow(0);
            header.createCell(0).setCellValue("City");
            header.createCell(1).setCellValue("Provider");
            header.createCell(2).setCellValue("Property Name");
            header.createCell(3).setCellValue("Room Type\u00a0");
            header.createCell(4).setCellValue("18-26 Weeks");
            header.createCell(5).setCellValue("最早日期");
            var firstRoom = sheet.createRow(1);
            firstRoom.createCell(0).setCellValue("Manchester");
            firstRoom.createCell(1).setCellValue("HFS");
            firstRoom.createCell(2).setCellValue("Riverside House");
            firstRoom.createCell(3).setCellValue("Classic Ensuite");
            firstRoom.createCell(4).setCellValue(257);
            firstRoom.createCell(5).setCellValue("2026.8.30");
            var secondRoom = sheet.createRow(2);
            secondRoom.createCell(3).setCellValue("Classic Accessible Ensuite");
            secondRoom.createCell(4).setCellValue(262);
            secondRoom.createCell(5).setCellValue("2026.8.30");
            try (OutputStream output = Files.newOutputStream(file)) {
                workbook.write(output);
            }
        }

        List<Document> documents = reader.read(file);

        assertThat(documents).hasSize(2);
        assertThat(documents.get(1).getText())
                .startsWith("## Riverside House")
                .contains("### Classic Accessible Ensuite",
                        "- **City**: Manchester",
                        "- **Provider**: HFS",
                        "- **18-26 Weeks**: 262",
                        "- **最早日期**: 2026.8.30");
        assertThat(documents.get(1).getMetadata())
                .containsEntry("propertyName", "Riverside House")
                .containsEntry("roomType", "Classic Accessible Ensuite");
    }
}
