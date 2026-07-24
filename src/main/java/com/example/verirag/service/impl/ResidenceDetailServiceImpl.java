package com.example.verirag.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.example.verirag.dto.ResidenceDetailImportResult;
import com.example.verirag.dto.ResidenceDetailSaveRequest;
import com.example.verirag.dto.ResidenceDetailSourceData;
import com.example.verirag.dto.ResidenceDetailView;
import com.example.verirag.dto.ResidenceNearbyPlaceRequest;
import com.example.verirag.dto.ResidenceNearbyPlaceSourceData;
import com.example.verirag.dto.ResidenceNearbyPlaceView;
import com.example.verirag.entity.Residence;
import com.example.verirag.entity.ResidenceDetail;
import com.example.verirag.entity.ResidenceNearbyPlace;
import com.example.verirag.exception.BusinessException;
import com.example.verirag.mapper.ResidenceDetailMapper;
import com.example.verirag.mapper.ResidenceMapper;
import com.example.verirag.mapper.ResidenceNearbyPlaceMapper;
import com.example.verirag.service.ResidenceDetailService;
import com.example.verirag.util.ResidenceDetailMarkdownReader;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.Normalizer;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class ResidenceDetailServiceImpl implements ResidenceDetailService {

    private static final long MAX_MARKDOWN_SIZE = 5 * 1024 * 1024;

    private final ResidenceMapper residenceMapper;
    private final ResidenceDetailMapper residenceDetailMapper;
    private final ResidenceNearbyPlaceMapper nearbyPlaceMapper;
    private final ResidenceDetailMarkdownReader markdownReader;

    @Override
    public ResidenceDetailView get(Long residenceId) {
        Residence residence = requireResidence(residenceId);
        ResidenceDetail detail = residenceDetailMapper.selectById(residenceId);
        List<ResidenceNearbyPlace> nearby = nearbyPlaceMapper.selectList(
                new LambdaQueryWrapper<ResidenceNearbyPlace>()
                        .eq(ResidenceNearbyPlace::getResidenceId, residenceId)
                        .orderByAsc(ResidenceNearbyPlace::getPlaceType)
                        .orderByAsc(ResidenceNearbyPlace::getSortOrder)
                        .orderByAsc(ResidenceNearbyPlace::getId));
        return toView(residence, detail, nearby);
    }

    @Override
    @Transactional
    public void save(ResidenceDetailSaveRequest request) {
        Residence residence = requireResidence(request.residenceId());
        ResidenceDetail detail = residenceDetailMapper.selectById(residence.getId());
        boolean insert = detail == null;
        if (insert) {
            detail = new ResidenceDetail();
            detail.setResidenceId(residence.getId());
        }
        detail.setOfficialId(blankToNull(request.officialId()));
        detail.setPostcode(blankToNull(request.postcode()));
        detail.setTransportLines(blankToNull(request.transportLines()));
        detail.setOfficialUrl(blankToNull(request.officialUrl()));
        detail.setPageTags(blankToNull(request.pageTags()));
        detail.setFacilities(joinFacilities(request.facilities()));
        detail.setDetailUpdatedAt(LocalDateTime.now());
        if (insert) {
            detail.setSourceFileName("manual");
            residenceDetailMapper.insert(detail);
        }
        else {
            residenceDetailMapper.updateById(detail);
        }
        replaceNearby(residence.getId(), toSource(request.nearbyPlaces()));
    }

    @Override
    @Transactional
    public ResidenceDetailImportResult importMarkdown(MultipartFile file) {
        validateMarkdown(file);
        String fileName = Objects.requireNonNullElse(
                file.getOriginalFilename(), "residence-details.md");
        if (fileName.length() > 255) {
            fileName = fileName.substring(fileName.length() - 255);
        }
        Path temporary = null;
        try {
            temporary = Files.createTempFile("residence-details-", ".md");
            file.transferTo(temporary);
            List<ResidenceDetailSourceData> sources = markdownReader.read(temporary);
            List<Residence> residences = residenceMapper.selectList(null);
            Map<String, Residence> bySourceId = residences.stream()
                    .filter(item -> item.getSourceId() != null)
                    .collect(Collectors.toMap(
                            item -> item.getSourceId().toLowerCase(Locale.ROOT),
                            Function.identity(),
                            (first, ignored) -> first,
                            LinkedHashMap::new));
            Map<String, Residence> byName = residences.stream()
                    .collect(Collectors.toMap(
                            item -> canonicalName(item.getName()),
                            Function.identity(),
                            (first, ignored) -> first,
                            LinkedHashMap::new));
            int imported = 0;
            List<String> warnings = new ArrayList<>();
            for (ResidenceDetailSourceData source : sources) {
                Residence residence = source.sourceId() == null
                        ? null
                        : bySourceId.get(source.sourceId().toLowerCase(Locale.ROOT));
                if (residence == null) {
                    residence = byName.get(canonicalName(source.name()));
                }
                if (residence == null) {
                    if (source.sourceId() == null || source.sourceId().isBlank()) {
                        warnings.add("缺少官网公寓 ID，无法新增：" + source.name());
                        continue;
                    }
                    residence = createResidence(source, fileName);
                    bySourceId.put(source.sourceId().toLowerCase(Locale.ROOT), residence);
                    byName.put(canonicalName(source.name()), residence);
                }
                applyResidenceFields(residence, source);
                residenceMapper.updateById(residence);
                upsertDetail(residence.getId(), source, fileName);
                mergeNearbyFromImport(residence.getId(), source.nearbyPlaces());
                imported++;
            }
            return new ResidenceDetailImportResult(
                    sources.size(), imported, sources.size() - imported,
                    fileName, List.copyOf(warnings));
        }
        catch (IllegalArgumentException ex) {
            throw new BusinessException(ex.getMessage());
        }
        catch (IOException ex) {
            throw new BusinessException("读取公寓详情 Markdown 失败");
        }
        finally {
            if (temporary != null) {
                try {
                    Files.deleteIfExists(temporary);
                }
                catch (IOException ignored) {
                    // 临时文件由系统兜底清理。
                }
            }
        }
    }

    private void upsertDetail(Long residenceId, ResidenceDetailSourceData source,
                              String fileName) {
        ResidenceDetail detail = residenceDetailMapper.selectById(residenceId);
        boolean insert = detail == null;
        if (insert) {
            detail = new ResidenceDetail();
            detail.setResidenceId(residenceId);
        }
        detail.setOfficialId(blankToNull(source.officialId()));
        detail.setPostcode(blankToNull(source.postcode()));
        detail.setTransportLines(blankToNull(source.transportLines()));
        detail.setOfficialUrl(blankToNull(source.officialUrl()));
        detail.setPageTags(blankToNull(source.pageTags()));
        detail.setFacilities(joinFacilities(source.facilities()));
        detail.setDetailMarkdown(blankToNull(source.detailMarkdown()));
        detail.setSourceFileName(fileName);
        detail.setDetailUpdatedAt(LocalDateTime.now());
        if (insert) {
            residenceDetailMapper.insert(detail);
        }
        else {
            residenceDetailMapper.updateById(detail);
        }
    }

    private void replaceNearby(Long residenceId,
                               List<ResidenceNearbyPlaceSourceData> nearbyPlaces) {
        nearbyPlaceMapper.delete(new LambdaQueryWrapper<ResidenceNearbyPlace>()
                .eq(ResidenceNearbyPlace::getResidenceId, residenceId));
        if (nearbyPlaces == null) {
            return;
        }
        for (ResidenceNearbyPlaceSourceData source : nearbyPlaces) {
            if (source == null || source.placeName() == null
                    || source.placeName().isBlank()) {
                continue;
            }
            ResidenceNearbyPlace place = new ResidenceNearbyPlace();
            place.setResidenceId(residenceId);
            place.setPlaceType(source.placeType());
            place.setPlaceName(source.placeName().strip());
            place.setTravelDescription(blankToNull(source.travelDescription()));
            place.setMinMinutes(source.minMinutes());
            place.setMaxMinutes(source.maxMinutes());
            place.setTravelMode(source.travelMode());
            place.setDistanceMiles(source.distanceMiles());
            place.setSortOrder(Objects.requireNonNullElse(source.sortOrder(), 0));
            nearbyPlaceMapper.insert(place);
        }
    }

    /**
     * Markdown 是增量数据源：更新同一路线，但保留后台为同一地点补充的其它交通方式。
     * 例如源文件只有 BUS，后台补充的 BIKE/WALK 不会在下次导入时丢失。
     */
    private void mergeNearbyFromImport(
            Long residenceId,
            List<ResidenceNearbyPlaceSourceData> importedPlaces) {
        List<ResidenceNearbyPlaceSourceData> merged = new ArrayList<>(
                importedPlaces == null ? List.of() : importedPlaces);
        Set<String> importedPlaceKeys = merged.stream()
                .map(ResidenceDetailServiceImpl::placeKey)
                .collect(Collectors.toSet());
        Set<String> importedRouteKeys = merged.stream()
                .map(ResidenceDetailServiceImpl::routeKey)
                .collect(Collectors.toSet());
        List<ResidenceNearbyPlace> existing = nearbyPlaceMapper.selectList(
                new LambdaQueryWrapper<ResidenceNearbyPlace>()
                        .eq(ResidenceNearbyPlace::getResidenceId, residenceId)
                        .orderByAsc(ResidenceNearbyPlace::getSortOrder));
        for (ResidenceNearbyPlace place : existing) {
            ResidenceNearbyPlaceSourceData source = new ResidenceNearbyPlaceSourceData(
                    place.getPlaceType(), place.getPlaceName(),
                    place.getTravelDescription(), place.getMinMinutes(),
                    place.getMaxMinutes(), place.getTravelMode(),
                    place.getDistanceMiles(), place.getSortOrder());
            if (importedPlaceKeys.contains(placeKey(source))
                    && importedRouteKeys.add(routeKey(source))) {
                merged.add(source);
            }
        }
        replaceNearby(residenceId, merged);
    }

    private static String placeKey(ResidenceNearbyPlaceSourceData place) {
        return normalizeKey(place.placeType()) + "|" + normalizeKey(place.placeName());
    }

    private static String routeKey(ResidenceNearbyPlaceSourceData place) {
        return placeKey(place)
                + "|" + normalizeKey(place.travelMode());
    }

    private static String normalizeKey(String value) {
        return Normalizer.normalize(Objects.toString(value, ""), Normalizer.Form.NFKD)
                .replaceAll("\\p{M}+", "")
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "");
    }

    private static List<ResidenceNearbyPlaceSourceData> toSource(
            List<ResidenceNearbyPlaceRequest> requests) {
        if (requests == null) {
            return List.of();
        }
        List<ResidenceNearbyPlaceSourceData> result = new ArrayList<>();
        int index = 0;
        for (ResidenceNearbyPlaceRequest request : requests) {
            if (request == null) {
                continue;
            }
            int baseOrder = Objects.requireNonNullElse(request.sortOrder(), index);
            var options = ResidenceDetailMarkdownReader.travelOptions(
                    request.travelDescription());
            for (int optionIndex = 0; optionIndex < options.size(); optionIndex++) {
                var option = options.get(optionIndex);
                result.add(new ResidenceNearbyPlaceSourceData(
                        request.placeType(),
                        request.placeName(),
                        option.description(),
                        option.minMinutes(),
                        option.maxMinutes(),
                        option.travelMode(),
                        option.distanceMiles(),
                        baseOrder * 10 + optionIndex));
            }
            index++;
        }
        return List.copyOf(result);
    }

    private static void applyResidenceFields(
            Residence residence, ResidenceDetailSourceData source) {
        if (source.city() != null && !source.city().isBlank()) {
            residence.setCity(source.city().strip());
        }
        if (source.address() != null && !source.address().isBlank()) {
            residence.setAddress(source.address().strip());
        }
        if (source.zone() != null && !source.zone().isBlank()) {
            residence.setZone(source.zone().strip());
        }
        if (source.station() != null && !source.station().isBlank()) {
            residence.setStation(source.station().strip());
        }
    }

    private Residence createResidence(ResidenceDetailSourceData source, String fileName) {
        Residence residence = new Residence();
        residence.setSourceId(source.sourceId().strip().toLowerCase(Locale.ROOT));
        residence.setName(source.name().strip());
        residence.setCity(blankToNull(source.city()) == null ? "London" : source.city().strip());
        residence.setZone(blankToNull(source.zone()));
        residence.setAddress(blankToNull(source.address()));
        residence.setStation(blankToNull(source.station()));
        residence.setSourceFileName(fileName);
        residence.setActive(1);
        residenceMapper.insert(residence);
        return residence;
    }

    private Residence requireResidence(Long residenceId) {
        Residence residence = residenceId == null
                ? null : residenceMapper.selectById(residenceId);
        if (residence == null) {
            throw new BusinessException("公寓不存在");
        }
        return residence;
    }

    private static ResidenceDetailView toView(
            Residence residence, ResidenceDetail detail,
            List<ResidenceNearbyPlace> nearby) {
        return new ResidenceDetailView(
                residence.getId(),
                residence.getSourceId(),
                residence.getName(),
                residence.getCity(),
                detail == null ? null : detail.getOfficialId(),
                detail == null ? null : detail.getPostcode(),
                detail == null ? null : detail.getTransportLines(),
                detail == null ? null : detail.getOfficialUrl(),
                detail == null ? null : detail.getPageTags(),
                splitFacilities(detail == null ? null : detail.getFacilities()),
                nearby.stream().map(ResidenceDetailServiceImpl::toNearbyView).toList(),
                detail == null ? null : detail.getSourceFileName(),
                detail == null ? null : detail.getDetailUpdatedAt(),
                detail == null ? null : detail.getUpdateTime());
    }

    private static ResidenceNearbyPlaceView toNearbyView(ResidenceNearbyPlace place) {
        return new ResidenceNearbyPlaceView(
                place.getId(), place.getPlaceType(), place.getPlaceName(),
                place.getTravelDescription(), place.getMinMinutes(),
                place.getMaxMinutes(), place.getTravelMode(),
                place.getDistanceMiles(), place.getSortOrder());
    }

    private static String joinFacilities(List<String> facilities) {
        if (facilities == null) {
            return null;
        }
        String joined = facilities.stream()
                .filter(Objects::nonNull)
                .map(String::strip)
                .filter(item -> !item.isBlank())
                .distinct()
                .collect(Collectors.joining("\n"));
        return joined.isBlank() ? null : joined;
    }

    private static List<String> splitFacilities(String facilities) {
        if (facilities == null || facilities.isBlank()) {
            return List.of();
        }
        return facilities.lines().map(String::strip)
                .filter(item -> !item.isBlank()).toList();
    }

    private static String canonicalName(String value) {
        return Normalizer.normalize(Objects.toString(value, ""), Normalizer.Form.NFKD)
                .replaceAll("\\p{M}+", "")
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "")
                .replaceFirst("^(?:chapter|prestige|fresh|downing|mezzino|fusion|unitestudents)", "")
                .replaceFirst("(?:residence|studentaccommodation)$", "");
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    private static void validateMarkdown(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new BusinessException("请选择 Markdown 文件");
        }
        String fileName = Objects.requireNonNullElse(file.getOriginalFilename(), "");
        if (!fileName.toLowerCase(Locale.ROOT).endsWith(".md")) {
            throw new BusinessException("公寓详情数据源必须是 Markdown 文件");
        }
        if (file.getSize() > MAX_MARKDOWN_SIZE) {
            throw new BusinessException("Markdown 文件不能超过5MB");
        }
    }
}
