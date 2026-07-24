package com.example.verirag.dto;

import java.util.List;

public record ResidenceDetailImportResult(
        int total,
        int imported,
        int unmatched,
        String fileName,
        List<String> warnings
) {
}
