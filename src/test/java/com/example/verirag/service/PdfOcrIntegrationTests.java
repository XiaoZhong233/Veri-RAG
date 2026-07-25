package com.example.verirag.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Optional real-Tesseract check. Supply an image-only PDF with -Docr.fixture=/path/to/file.pdf.
 */
@EnabledIfSystemProperty(named = "ocr.fixture", matches = ".+")
class PdfOcrIntegrationTests {

    @Test
    void extractsTextFromImageOnlyPdf() {
        Path pdf = Path.of(System.getProperty("ocr.fixture"));
        PdfOcrService service = new PdfOcrService();
        ReflectionTestUtils.setField(service, "enabled", true);
        ReflectionTestUtils.setField(service, "language", "eng");
        ReflectionTestUtils.setField(service, "maxPages", 10);
        ReflectionTestUtils.setField(service, "dpi", 300);
        ReflectionTestUtils.setField(service, "timeoutSeconds", 30);

        String text = service.extractText(pdf, service.pageCount(pdf));

        assertThat(text.toUpperCase()).contains("EMPLOYEE", "VACATION", "POLICY", "2026");
    }
}
