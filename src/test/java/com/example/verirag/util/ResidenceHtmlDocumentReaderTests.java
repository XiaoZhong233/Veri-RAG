package com.example.verirag.util;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class ResidenceHtmlDocumentReaderTests {

    @TempDir
    Path tempDir;

    private final ResidenceHtmlDocumentReader reader = new ResidenceHtmlDocumentReader();

    @Test
    void convertsEmbeddedResidenceDataToMarkdown() throws Exception {
        Path file = tempDir.resolve("map.html");
        Files.writeString(file, """
                <html><body><script>
                const residences = {
                  londonbridge: {
                    name: "London Bridge Residence",
                    region: "south", zone: "Zone 1",
                    lat: 51.5030297, lng: -0.0852184,
                    address: "42 Weston Street, SE1 3QD",
                    station: "London Bridge – Jubilee & Northern (2 min)",
                    amenities: ["Gym", "Cinema", "Study Areas"],
                    attractions: [
                      { emoji: "🏙️", name: "The Shard", dist: "Next door" },
                      { emoji: "🍽️", name: "Borough Market", dist: "Walking distance" },
                    ],
                    universities: [
                      { name: "King's College London (Guy's)", dist: "5 min walk" },
                      { name: "London South Bank University", dist: "15 min walk" },
                    ],
                    mapUrl: "https://www.google.com/maps/search/?api=1&query=42+Weston"
                  }
                };
                </script></body></html>
                """);

        var documents = reader.read(file);

        assertThat(documents).hasSize(2);
        assertThat(documents.getFirst().getText())
                .contains("# Londonist 伦敦公寓位置总览",
                        "Londonist 在伦敦共有 **1 个公寓**",
                        "## 南伦敦（South London）（1 个）",
                        "- London Bridge Residence");
        assertThat(documents.getFirst().getMetadata())
                .containsEntry("contentType", "residencePortfolioSummary")
                .containsEntry("residenceCount", 1);

        assertThat(documents.get(1)).satisfies(document -> {
            assertThat(document.getText())
                    .contains("## London Bridge Residence",
                            "**区域**: 南伦敦（South London）",
                            "**交通分区**: Zone 1",
                            "**地址**: 42 Weston Street, SE1 3QD",
                            "### 配套设施", "- Gym",
                            "### 附近大学",
                            "- King's College London (Guy's)：5 min walk",
                            "### 周边地点", "- The Shard：Next door");
            assertThat(document.getMetadata())
                    .containsEntry("contentFormat", "markdown")
                    .containsEntry("residenceId", "londonbridge")
                    .containsEntry("residenceName", "London Bridge Residence")
                    .containsEntry("region", "south");
        });
    }
}
