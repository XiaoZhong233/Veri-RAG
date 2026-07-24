package com.example.verirag.controller;

import com.example.verirag.common.PageResult;
import com.example.verirag.common.R;
import com.example.verirag.dto.ResidenceImportResult;
import com.example.verirag.dto.ResidenceOption;
import com.example.verirag.dto.ResidenceSaveRequest;
import com.example.verirag.dto.ResidenceStats;
import com.example.verirag.entity.Residence;
import com.example.verirag.service.ResidenceService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

@RestController
@RequestMapping("/api/residences")
@RequiredArgsConstructor
public class ResidenceController {

    private final ResidenceService residenceService;

    @GetMapping
    public R<PageResult<Residence>> page(
            @RequestParam(defaultValue = "") String keyword,
            @RequestParam(defaultValue = "") String city,
            @RequestParam(defaultValue = "") String region,
            @RequestParam(defaultValue = "false") boolean includeInactive,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return R.ok(residenceService.page(keyword, city, region, includeInactive, page, size));
    }

    @GetMapping("/{id}")
    public R<Residence> get(@PathVariable Long id) {
        return R.ok(residenceService.get(id));
    }

    @GetMapping("/stats")
    public R<ResidenceStats> stats() {
        return R.ok(residenceService.stats());
    }

    @GetMapping("/options")
    public R<List<ResidenceOption>> options() {
        return R.ok(residenceService.options());
    }

    @PreAuthorize("hasRole('ADMIN')")
    @PostMapping
    public R<Void> save(@Valid @RequestBody ResidenceSaveRequest request) {
        residenceService.save(request);
        return R.ok();
    }

    @PreAuthorize("hasRole('ADMIN')")
    @DeleteMapping("/{id}")
    public R<Void> delete(@PathVariable Long id) {
        residenceService.delete(id);
        return R.ok();
    }

    @PreAuthorize("hasRole('ADMIN')")
    @PostMapping(value = "/import", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public R<ResidenceImportResult> importHtml(@RequestPart("file") MultipartFile file) {
        return R.ok(residenceService.importHtml(file));
    }
}
