package com.example.verirag.controller;

import com.example.verirag.common.R;
import com.example.verirag.dto.SalesRecommendationSaveRequest;
import com.example.verirag.dto.SalesRecommendationView;
import com.example.verirag.service.SalesRecommendationService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/sales-recommendations")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
public class SalesRecommendationController {

    private final SalesRecommendationService salesRecommendationService;

    @GetMapping
    public R<List<SalesRecommendationView>> list() {
        return R.ok(salesRecommendationService.list());
    }

    @GetMapping("/{id}")
    public R<SalesRecommendationView> get(@PathVariable Long id) {
        return R.ok(salesRecommendationService.get(id));
    }

    @PostMapping
    public R<Void> save(@Valid @RequestBody SalesRecommendationSaveRequest request) {
        salesRecommendationService.save(request);
        return R.ok();
    }

    @DeleteMapping("/{id}")
    public R<Void> delete(@PathVariable Long id) {
        salesRecommendationService.delete(id);
        return R.ok();
    }
}
