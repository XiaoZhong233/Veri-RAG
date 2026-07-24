package com.example.verirag.service;

import com.example.verirag.common.PageResult;
import com.example.verirag.dto.ResidenceImportResult;
import com.example.verirag.dto.ResidenceOption;
import com.example.verirag.dto.ResidenceSaveRequest;
import com.example.verirag.dto.ResidenceStats;
import com.example.verirag.entity.Residence;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

public interface ResidenceService {
    PageResult<Residence> page(String name, String keyword, String city, String region,
                               boolean includeInactive, int page, int size);

    Residence get(Long id);

    void save(ResidenceSaveRequest request);

    void delete(Long id);

    ResidenceStats stats();

    List<ResidenceOption> options();

    ResidenceImportResult importHtml(MultipartFile file);
}
