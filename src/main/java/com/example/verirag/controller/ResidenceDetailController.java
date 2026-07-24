package com.example.verirag.controller;

import com.example.verirag.common.R;
import com.example.verirag.dto.ResidenceDetailImportResult;
import com.example.verirag.dto.ResidenceDetailSaveRequest;
import com.example.verirag.dto.ResidenceDetailView;
import com.example.verirag.service.ResidenceDetailService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/residence-details")
@RequiredArgsConstructor
public class ResidenceDetailController {

    private final ResidenceDetailService residenceDetailService;

    @GetMapping("/{residenceId}")
    public R<ResidenceDetailView> get(@PathVariable Long residenceId) {
        return R.ok(residenceDetailService.get(residenceId));
    }

    @PreAuthorize("hasRole('ADMIN')")
    @PostMapping
    public R<Void> save(@Valid @RequestBody ResidenceDetailSaveRequest request) {
        residenceDetailService.save(request);
        return R.ok();
    }

    @PreAuthorize("hasRole('ADMIN')")
    @PostMapping(value = "/import", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public R<ResidenceDetailImportResult> importMarkdown(
            @RequestPart("file") MultipartFile file) {
        return R.ok(residenceDetailService.importMarkdown(file));
    }
}
