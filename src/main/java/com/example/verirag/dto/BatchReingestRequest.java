package com.example.verirag.dto;

import jakarta.validation.constraints.NotEmpty;
import lombok.Data;

import java.util.List;

/** 批量重新向量化请求。 */
@Data
public class BatchReingestRequest {

    @NotEmpty(message = "Please select at least one document")
    private List<Long> documentIds;
}
