package com.example.verirag.service.impl;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class RagIngestResidenceChunkTests {

    @Test
    void writesResidenceIdentityIntoEveryStoredChunk() {
        String result = RagIngestServiceImpl.contextualizeResidenceChunkForStorage(
                "- University College London (UCL): 18 minutes via tube",
                Map.of(
                        "residenceName", "Highbury Residence",
                        "zone", "Zone 2",
                        "address", "309 Holloway Road"));

        assertThat(result)
                .startsWith("公寓名称：Highbury Residence")
                .contains("交通分区：Zone 2")
                .contains("地址：309 Holloway Road")
                .contains("University College London (UCL)");
    }
}
