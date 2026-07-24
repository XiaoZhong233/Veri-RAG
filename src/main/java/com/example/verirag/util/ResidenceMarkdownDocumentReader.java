package com.example.verirag.util;

import org.springframework.ai.document.Document;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads the generated Londonist residence portfolio Markdown by residence.
 *
 * <p>The standard Spring AI Markdown reader creates a document at every heading
 * but does not carry a parent H2 heading into H3 documents. As a result a
 * "nearby universities" chunk can lose the residence it belongs to. This reader
 * keeps each H2 residence section together and stores the residence name in
 * metadata, which is then inherited by every token chunk.</p>
 */
@Component
public class ResidenceMarkdownDocumentReader {

    private static final String PORTFOLIO_MARKER = "# Londonist 伦敦学生公寓资料";
    private static final String INDEX_HEADING = "公寓索引";
    private static final Pattern H2 = Pattern.compile("(?m)^##\\s+(.+?)\\s*$");

    public boolean supports(Path path) {
        if (path == null || !Files.isRegularFile(path)) {
            return false;
        }
        try {
            String markdown = Files.readString(path, StandardCharsets.UTF_8);
            return markdown.contains(PORTFOLIO_MARKER)
                    && markdown.contains("## " + INDEX_HEADING)
                    && markdown.contains("### 附近学校/大学");
        }
        catch (IOException ex) {
            return false;
        }
    }

    public List<Document> read(Path path) {
        try {
            return parse(Files.readString(path, StandardCharsets.UTF_8));
        }
        catch (IOException ex) {
            throw new IllegalArgumentException("Failed to read residence Markdown file", ex);
        }
    }

    List<Document> parse(String markdown) {
        if (markdown == null || markdown.isBlank()) {
            return List.of();
        }

        Matcher matcher = H2.matcher(markdown);
        List<Section> sections = new ArrayList<>();
        while (matcher.find()) {
            sections.add(new Section(matcher.group(1).strip(), matcher.start()));
        }
        if (sections.isEmpty()) {
            return List.of(new Document(markdown.strip()));
        }

        List<Document> documents = new ArrayList<>();
        String preamble = markdown.substring(0, sections.getFirst().start()).strip();
        if (!preamble.isBlank()) {
            documents.add(new Document(preamble,
                    metadata(null, "residencePortfolioSummary", preamble)));
        }

        for (int i = 0; i < sections.size(); i++) {
            Section section = sections.get(i);
            int end = i + 1 < sections.size() ? sections.get(i + 1).start() : markdown.length();
            String text = markdown.substring(section.start(), end).strip();
            boolean index = INDEX_HEADING.equals(section.title());
            documents.add(new Document(text, metadata(
                    index ? null : section.title(),
                    index ? "residencePortfolioIndex" : "residenceDetail",
                    text)));
        }
        return documents;
    }

    private Map<String, Object> metadata(
            String residenceName, String contentType, String sectionText) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("contentFormat", "markdown");
        metadata.put("contentType", contentType);
        if (residenceName != null) {
            metadata.put("residenceName", residenceName);
        }
        putField(metadata, "address", sectionText, "地址");
        putField(metadata, "zone", sectionText, "区域");
        putField(metadata, "station", sectionText, "最近车站");
        return metadata;
    }

    private void putField(
            Map<String, Object> metadata, String key, String text, String label) {
        Matcher matcher = Pattern.compile(
                "(?m)^-\\s*" + Pattern.quote(label) + "：\\s*(.+?)\\s*$")
                .matcher(text);
        if (matcher.find()) {
            metadata.put(key, matcher.group(1).strip());
        }
    }

    private record Section(String title, int start) {
    }
}
