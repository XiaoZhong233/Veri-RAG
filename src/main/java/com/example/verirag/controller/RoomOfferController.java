package com.example.verirag.controller;

import com.example.verirag.common.PageResult;
import com.example.verirag.common.R;
import com.example.verirag.dto.RoomOfferImportResult;
import com.example.verirag.dto.RoomOfferSaveRequest;
import com.example.verirag.dto.RoomOfferStats;
import com.example.verirag.dto.RoomOfferView;
import com.example.verirag.entity.OfferImportBatch;
import com.example.verirag.service.RoomOfferService;
import com.example.verirag.util.SecurityUtils;
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
@RequestMapping("/api/room-offers")
@RequiredArgsConstructor
public class RoomOfferController {

    private final RoomOfferService roomOfferService;

    @GetMapping("/page")
    public R<PageResult<RoomOfferView>> page(
            @RequestParam(defaultValue = "") String keyword,
            @RequestParam(required = false) Long residenceId,
            @RequestParam(defaultValue = "") String status,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return R.ok(roomOfferService.page(keyword, residenceId, status, page, size));
    }

    @GetMapping("/stats")
    public R<RoomOfferStats> stats() {
        return R.ok(roomOfferService.stats());
    }

    @GetMapping("/imports")
    public R<List<OfferImportBatch>> recentImports(
            @RequestParam(defaultValue = "5") int limit) {
        return R.ok(roomOfferService.recentImports(limit));
    }

    @GetMapping("/{id}")
    public R<RoomOfferView> get(@PathVariable Long id) {
        return R.ok(roomOfferService.get(id));
    }

    @PreAuthorize("hasRole('ADMIN')")
    @PostMapping
    public R<Void> save(@Valid @RequestBody RoomOfferSaveRequest request) {
        roomOfferService.save(request);
        return R.ok();
    }

    @PreAuthorize("hasRole('ADMIN')")
    @DeleteMapping("/{id}")
    public R<Void> delete(@PathVariable Long id) {
        roomOfferService.delete(id);
        return R.ok();
    }

    @PreAuthorize("hasRole('ADMIN')")
    @PostMapping(value = "/import", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public R<RoomOfferImportResult> importWorkbook(@RequestPart("file") MultipartFile file) {
        Long userId = SecurityUtils.requireUser().getUserId();
        return R.ok(roomOfferService.importWorkbook(file, userId));
    }
}
