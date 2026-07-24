package com.example.verirag.util;

import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ResidenceMarkdownDocumentReaderTests {

    private final ResidenceMarkdownDocumentReader reader =
            new ResidenceMarkdownDocumentReader();

    @Test
    void keepsUniversitySectionWithItsResidenceMetadata() {
        String markdown = """
                # Londonist 伦敦学生公寓资料

                ## 公寓索引

                | 公寓 |
                |---|
                | Islington Residence |

                ## Islington Residence

                - 地址：London N7

                ### 附近学校/大学

                - University College London (UCL): 15 minutes via tube

                ## Drapery Place

                ### 附近学校/大学

                - Queen Mary University: 10 minutes walk
                """;

        List<Document> documents = reader.parse(markdown);

        assertThat(documents).hasSize(4);
        Document islington = documents.get(2);
        assertThat(islington.getMetadata())
                .containsEntry("residenceName", "Islington Residence")
                .containsEntry("contentType", "residenceDetail")
                .containsEntry("address", "London N7");
        assertThat(islington.getText())
                .contains("## Islington Residence")
                .contains("University College London (UCL): 15 minutes via tube");

        Document drapery = documents.get(3);
        assertThat(drapery.getMetadata())
                .containsEntry("residenceName", "Drapery Place");
        assertThat(drapery.getText()).doesNotContain("University College London");
    }
}
