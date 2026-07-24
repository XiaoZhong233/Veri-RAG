package com.example.verirag.service;

import com.example.verirag.dto.SalesRecommendationSaveRequest;
import com.example.verirag.dto.SalesRecommendationView;

import java.util.List;

public interface SalesRecommendationService {
    List<SalesRecommendationView> list();

    SalesRecommendationView get(Long id);

    void save(SalesRecommendationSaveRequest request);

    void delete(Long id);

    List<SalesRecommendationView> enabledRecommendations();
}
