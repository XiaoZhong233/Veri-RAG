package com.example.verirag.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.example.verirag.dto.SalesRecommendationSaveRequest;
import com.example.verirag.dto.SalesRecommendationView;
import com.example.verirag.entity.Residence;
import com.example.verirag.entity.SalesRecommendation;
import com.example.verirag.exception.BusinessException;
import com.example.verirag.mapper.ResidenceMapper;
import com.example.verirag.mapper.SalesRecommendationMapper;
import com.example.verirag.service.SalesRecommendationService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class SalesRecommendationServiceImpl implements SalesRecommendationService {

    private final SalesRecommendationMapper salesRecommendationMapper;
    private final ResidenceMapper residenceMapper;

    @Override
    public List<SalesRecommendationView> list() {
        return toViews(salesRecommendationMapper.selectList(
                recommendationOrder()));
    }

    @Override
    public SalesRecommendationView get(Long id) {
        SalesRecommendation recommendation = requireRecommendation(id);
        Residence residence = residenceMapper.selectById(recommendation.getResidenceId());
        if (residence == null) {
            throw new BusinessException("推荐关联的公寓不存在");
        }
        return toView(recommendation, residence);
    }

    @Override
    @Transactional
    public void save(SalesRecommendationSaveRequest request) {
        Residence residence = residenceMapper.selectById(request.residenceId());
        if (residence == null) {
            throw new BusinessException("推荐公寓不存在");
        }
        if (!Objects.equals(residence.getActive(), 1)) {
            throw new BusinessException("停用公寓不能设为优先推荐");
        }

        Long duplicateId = salesRecommendationMapper.selectList(
                        new LambdaQueryWrapper<SalesRecommendation>()
                                .eq(SalesRecommendation::getResidenceId, request.residenceId()))
                .stream()
                .map(SalesRecommendation::getId)
                .filter(id -> !Objects.equals(id, request.id()))
                .findFirst()
                .orElse(null);
        if (duplicateId != null) {
            throw new BusinessException("该公寓已经存在推荐配置");
        }

        SalesRecommendation recommendation = request.id() == null
                ? new SalesRecommendation()
                : requireRecommendation(request.id());
        recommendation.setResidenceId(request.residenceId());
        recommendation.setPriority(request.priority());
        recommendation.setEnabled(request.enabled() == null || request.enabled() != 0 ? 1 : 0);
        recommendation.setNote(blankToNull(request.note()));
        if (request.id() == null) {
            salesRecommendationMapper.insert(recommendation);
        }
        else {
            salesRecommendationMapper.updateById(recommendation);
        }
    }

    @Override
    @Transactional
    public void delete(Long id) {
        salesRecommendationMapper.deleteById(requireRecommendation(id));
    }

    @Override
    public List<SalesRecommendationView> enabledRecommendations() {
        List<SalesRecommendation> recommendations = salesRecommendationMapper.selectList(
                recommendationOrder().eq(SalesRecommendation::getEnabled, 1));
        if (recommendations.isEmpty()) {
            return List.of();
        }
        Map<Long, Residence> residences = residenceMapper.selectBatchIds(
                        recommendations.stream()
                                .map(SalesRecommendation::getResidenceId)
                                .toList())
                .stream()
                .filter(item -> Objects.equals(item.getActive(), 1))
                .collect(Collectors.toMap(
                        Residence::getId,
                        Function.identity(),
                        (first, ignored) -> first,
                        LinkedHashMap::new));
        return recommendations.stream()
                .filter(item -> residences.containsKey(item.getResidenceId()))
                .map(item -> toView(item, residences.get(item.getResidenceId())))
                .toList();
    }

    private List<SalesRecommendationView> toViews(
            List<SalesRecommendation> recommendations) {
        if (recommendations.isEmpty()) {
            return List.of();
        }
        Map<Long, Residence> residences = residenceMapper.selectBatchIds(
                        recommendations.stream()
                                .map(SalesRecommendation::getResidenceId)
                                .toList())
                .stream()
                .collect(Collectors.toMap(
                        Residence::getId,
                        Function.identity(),
                        (first, ignored) -> first,
                        LinkedHashMap::new));
        return recommendations.stream()
                .filter(item -> residences.containsKey(item.getResidenceId()))
                .map(item -> toView(item, residences.get(item.getResidenceId())))
                .toList();
    }

    private SalesRecommendation requireRecommendation(Long id) {
        SalesRecommendation recommendation = id == null
                ? null
                : salesRecommendationMapper.selectById(id);
        if (recommendation == null) {
            throw new BusinessException("推荐配置不存在");
        }
        return recommendation;
    }

    private static LambdaQueryWrapper<SalesRecommendation> recommendationOrder() {
        return new LambdaQueryWrapper<SalesRecommendation>()
                .orderByDesc(SalesRecommendation::getEnabled)
                .orderByAsc(SalesRecommendation::getPriority)
                .orderByAsc(SalesRecommendation::getId);
    }

    private static SalesRecommendationView toView(
            SalesRecommendation recommendation, Residence residence) {
        return new SalesRecommendationView(
                recommendation.getId(),
                residence.getId(),
                residence.getSourceId(),
                residence.getName(),
                residence.getCity(),
                recommendation.getPriority(),
                recommendation.getEnabled(),
                recommendation.getNote(),
                recommendation.getCreateTime(),
                recommendation.getUpdateTime());
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
