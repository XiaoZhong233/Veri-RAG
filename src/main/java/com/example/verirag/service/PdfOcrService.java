package com.example.verirag.service;

import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.parser.AutoDetectParser;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.parser.ocr.TesseractOCRConfig;
import org.apache.tika.parser.ocr.TesseractOCRParser;
import org.apache.tika.parser.pdf.PDFParserConfig;
import org.apache.tika.sax.BodyContentHandler;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 对文本层过少的扫描 PDF 执行 Tesseract OCR。
 *
 * <p>Tika/PDFBox 负责页面渲染和解析，Tesseract 可执行文件及所需语言包必须安装在系统 PATH 中。</p>
 */
@Slf4j
@Service
public class PdfOcrService {

    @Value("${rag.ocr.enabled:true}")
    private boolean enabled;

    @Value("${rag.ocr.language:chi_sim+eng}")
    private String language;

    @Value("${rag.ocr.min-chars-per-page:40}")
    private int minCharsPerPage;

    @Value("${rag.ocr.max-pages:50}")
    private int maxPages;

    @Value("${rag.ocr.dpi:300}")
    private int dpi;

    @Value("${rag.ocr.timeout-seconds:120}")
    private int timeoutSeconds;

    public boolean isEnabled() {
        return enabled;
    }

    /**
     * 文本层平均每页少于阈值时判定为扫描件或图片型 PDF。
     */
    public boolean needsOcr(int pageCount, long extractedCharacters) {
        int safePages = Math.max(pageCount, 1);
        long requiredCharacters = (long) safePages * Math.max(minCharsPerPage, 1);
        return extractedCharacters < requiredCharacters;
    }

    public int pageCount(Path pdfPath) {
        try (PDDocument document = Loader.loadPDF(pdfPath.toFile())) {
            return document.getNumberOfPages();
        }
        catch (Exception ex) {
            throw new IllegalArgumentException("Unable to inspect PDF pages: " + ex.getMessage(), ex);
        }
    }

    /**
     * 只读取 PDF 自带文本层，显式禁用 OCR，避免普通 PDF 产生额外处理开销。
     */
    public String extractTextLayer(Path pdfPath) {
        PDFParserConfig pdfConfig = new PDFParserConfig();
        pdfConfig.setOcrStrategy(PDFParserConfig.OCR_STRATEGY.NO_OCR);
        pdfConfig.setSortByPosition(true);
        return parse(pdfPath, pdfConfig, null, "PDF text extraction");
    }

    public String extractText(Path pdfPath, int pageCount) {
        if (!enabled) {
            throw new IllegalStateException(
                    "This PDF has no usable text layer and OCR is disabled (rag.ocr.enabled=false)");
        }
        if (pageCount > Math.max(maxPages, 1)) {
            throw new IllegalArgumentException(
                    "Scanned PDF has " + pageCount + " pages; OCR limit is " + maxPages
                            + ". Increase RAG_OCR_MAX_PAGES only after reviewing resource limits.");
        }

        ensureTesseractAvailable();

        PDFParserConfig pdfConfig = new PDFParserConfig();
        // 保留少量已有文本层，同时识别扫描页；适用于混合型 PDF。
        pdfConfig.setOcrStrategy(PDFParserConfig.OCR_STRATEGY.OCR_AND_TEXT_EXTRACTION);
        pdfConfig.setOcrDPI(Math.max(dpi, 72));
        pdfConfig.setSortByPosition(true);

        TesseractOCRConfig ocrConfig = new TesseractOCRConfig();
        ocrConfig.setLanguage(language);
        ocrConfig.setTimeoutSeconds(Math.max(timeoutSeconds, 1));
        ocrConfig.setPreserveInterwordSpacing(true);

        long started = System.nanoTime();
        String text = parse(pdfPath, pdfConfig, ocrConfig,
                "OCR (verify Tesseract language data '" + language + "')");
        if (text.isBlank()) {
            throw new IllegalStateException(
                    "OCR completed but produced no text. Check scan quality and installed Tesseract languages.");
        }
        long elapsedMs = (System.nanoTime() - started) / 1_000_000L;
        log.info("event=document.ocr.completed file={} pages={} characters={} durationMs={} language={}",
                pdfPath.getFileName(), pageCount, visibleCharacterCount(text), elapsedMs, language);
        return text;
    }

    private String parse(Path pdfPath, PDFParserConfig pdfConfig,
                         TesseractOCRConfig ocrConfig, String operation) {
        ParseContext context = new ParseContext();
        context.set(PDFParserConfig.class, pdfConfig);
        if (ocrConfig != null) {
            context.set(TesseractOCRConfig.class, ocrConfig);
        }

        BodyContentHandler handler = new BodyContentHandler(-1);
        Metadata metadata = new Metadata();
        try (InputStream input = Files.newInputStream(pdfPath)) {
            new AutoDetectParser().parse(input, handler, metadata, context);
            return normalize(handler.toString());
        }
        catch (Exception ex) {
            throw new IllegalStateException(operation + " failed: " + ex.getMessage(), ex);
        }
    }

    private void ensureTesseractAvailable() {
        try {
            if (!new TesseractOCRParser().hasTesseract()) {
                throw missingTesseract();
            }
        }
        catch (IllegalStateException ex) {
            throw ex;
        }
        catch (Exception ex) {
            throw new IllegalStateException(
                    "Unable to check Tesseract installation. Ensure 'tesseract' is available on PATH.", ex);
        }
    }

    private static IllegalStateException missingTesseract() {
        return new IllegalStateException(
                "This PDF requires OCR, but Tesseract is not installed or is not available on PATH.");
    }

    public static long visibleCharacterCount(String text) {
        if (text == null || text.isBlank()) {
            return 0;
        }
        return text.codePoints().filter(codePoint -> !Character.isWhitespace(codePoint)).count();
    }

    private static String normalize(String text) {
        if (text == null) {
            return "";
        }
        return text.replace("\r\n", "\n")
                .replace('\r', '\n')
                .replaceAll("[\\t\\x0B\\f ]+", " ")
                .replaceAll("\\n{3,}", "\n\n")
                .strip();
    }
}
