package com.example.verirag.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class PdfOcrServiceTests {

    @TempDir
    Path tempDir;

    @Test
    void detectsScannedPdfUsingAverageVisibleCharactersPerPage() {
        PdfOcrService service = new PdfOcrService();
        ReflectionTestUtils.setField(service, "minCharsPerPage", 40);

        assertThat(service.needsOcr(3, 119)).isTrue();
        assertThat(service.needsOcr(3, 120)).isFalse();
    }

    @Test
    void countsChineseAndEnglishCharactersButNotWhitespace() {
        assertThat(PdfOcrService.visibleCharacterCount("员工 handbook\n 2026")).isEqualTo(14);
        assertThat(PdfOcrService.visibleCharacterCount(" \n\t")).isZero();
        assertThat(PdfOcrService.visibleCharacterCount(null)).isZero();
    }

    @Test
    void extractsExistingTextLayerWithoutTesseract() throws Exception {
        Path pdf = tempDir.resolve("text-layer.pdf");
        try (PDDocument document = new PDDocument()) {
            PDPage page = new PDPage();
            document.addPage(page);
            try (PDPageContentStream content = new PDPageContentStream(document, page)) {
                content.beginText();
                content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                content.newLineAtOffset(72, 700);
                content.showText("Employee handbook policy 2026");
                content.endText();
            }
            document.save(pdf.toFile());
        }

        PdfOcrService service = new PdfOcrService();
        assertThat(service.pageCount(pdf)).isEqualTo(1);
        assertThat(service.extractTextLayer(pdf)).contains("Employee handbook policy 2026");
    }
}
