package com.example.verirag.service;

import com.example.verirag.dto.ResidenceDetailImportResult;
import com.example.verirag.dto.ResidenceDetailSaveRequest;
import com.example.verirag.dto.ResidenceDetailView;
import org.springframework.web.multipart.MultipartFile;

public interface ResidenceDetailService {
    ResidenceDetailView get(Long residenceId);

    void save(ResidenceDetailSaveRequest request);

    ResidenceDetailImportResult importMarkdown(MultipartFile file);
}
