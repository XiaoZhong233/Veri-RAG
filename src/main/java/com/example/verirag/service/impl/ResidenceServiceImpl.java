package com.example.verirag.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.example.verirag.common.PageResult;
import com.example.verirag.dto.ResidenceImportResult;
import com.example.verirag.dto.ResidenceOption;
import com.example.verirag.dto.ResidenceSaveRequest;
import com.example.verirag.dto.ResidenceSourceData;
import com.example.verirag.dto.ResidenceStats;
import com.example.verirag.entity.Residence;
import com.example.verirag.exception.BusinessException;
import com.example.verirag.mapper.ResidenceMapper;
import com.example.verirag.mapper.RoomInventoryMapper;
import com.example.verirag.service.ResidenceService;
import com.example.verirag.util.ResidenceHtmlDocumentReader;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class ResidenceServiceImpl implements ResidenceService {

    private static final long MAX_HTML_SIZE = 10 * 1024 * 1024;
    private static final String DEFAULT_CITY = "London";

    private final ResidenceMapper residenceMapper;
    private final RoomInventoryMapper roomInventoryMapper;
    private final ResidenceHtmlDocumentReader residenceHtmlDocumentReader;

    @Override
    public PageResult<Residence> page(String name, String keyword, String city,
                                      String region, boolean includeInactive,
                                      int page, int size) {
        int safePage = Math.max(page, 1);
        int safeSize = Math.min(Math.max(size, 1), 100);
        String nameQuery = name == null ? "" : name.trim();
        String query = keyword == null ? "" : keyword.trim();
        String normalizedCity = city == null ? "" : city.trim();
        String normalizedRegion = region == null ? "" : region.trim().toLowerCase(Locale.ROOT);

        LambdaQueryWrapper<Residence> wrapper = new LambdaQueryWrapper<Residence>()
                .eq(!includeInactive, Residence::getActive, 1)
                .like(!nameQuery.isBlank(), Residence::getName, nameQuery)
                .eq(!normalizedCity.isBlank(), Residence::getCity, normalizedCity)
                .eq(!normalizedRegion.isBlank(), Residence::getRegion, normalizedRegion)
                .orderByAsc(Residence::getCity)
                .orderByAsc(Residence::getRegion)
                .orderByAsc(Residence::getName);
        if (!query.isBlank()) {
            wrapper.and(condition -> condition
                    .like(Residence::getName, query)
                    .or()
                    .like(Residence::getCity, query)
                    .or()
                    .like(Residence::getAddress, query)
                    .or()
                    .like(Residence::getStation, query)
                    .or()
                    .like(Residence::getZone, query));
        }

        Page<Residence> result = residenceMapper.selectPage(
                new Page<>(safePage, safeSize), wrapper);
        return PageResult.of(result.getTotal(), result.getRecords());
    }

    @Override
    public Residence get(Long id) {
        Residence residence = id == null ? null : residenceMapper.selectById(id);
        if (residence == null) {
            throw new BusinessException("公寓不存在");
        }
        return residence;
    }

    @Override
    @Transactional
    public void save(ResidenceSaveRequest request) {
        Residence residence = request.id() == null ? new Residence() : get(request.id());
        String sourceId = request.sourceId().trim().toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("(^-|-$)", "");
        if (sourceId.isBlank()) {
            throw new BusinessException("公寓编码必须包含字母或数字");
        }
        Long duplicateId = residenceMapper.selectList(new LambdaQueryWrapper<Residence>()
                        .eq(Residence::getSourceId, sourceId)).stream()
                .map(Residence::getId)
                .filter(id -> !Objects.equals(id, request.id()))
                .findFirst()
                .orElse(null);
        if (duplicateId != null) {
            throw new BusinessException("公寓编码已存在");
        }
        validateCoordinates(request.latitude(), request.longitude());
        residence.setSourceId(sourceId);
        residence.setName(request.name().trim());
        residence.setCity(request.city().trim());
        residence.setRegion(normalizedRegion(request.region()));
        residence.setZone(blankToNull(request.zone()));
        residence.setAddress(request.address().trim());
        residence.setStation(blankToNull(request.station()));
        residence.setLatitude(request.latitude());
        residence.setLongitude(request.longitude());
        residence.setMapUrl(blankToNull(request.mapUrl()));
        residence.setActive(request.active() == null || request.active() != 0 ? 1 : 0);
        if (request.id() == null) {
            residence.setSourceFileName("manual");
            residenceMapper.insert(residence);
        }
        else {
            residenceMapper.updateById(residence);
        }
    }

    @Override
    @Transactional
    public void delete(Long id) {
        Residence residence = get(id);
        Long inventoryCount = roomInventoryMapper.selectCount(
                new LambdaQueryWrapper<com.example.verirag.entity.RoomInventory>()
                        .eq(com.example.verirag.entity.RoomInventory::getResidenceId, id));
        if (inventoryCount > 0) {
            throw new BusinessException("该公寓已关联房型库存，不能删除；可以将状态改为停用");
        }
        residenceMapper.deleteById(residence);
    }

    @Override
    public ResidenceStats stats() {
        List<Residence> active = residenceMapper.selectList(
                new LambdaQueryWrapper<Residence>().eq(Residence::getActive, 1));
        Map<String, Long> regions = active.stream()
                .collect(Collectors.groupingBy(
                        residence -> Objects.toString(residence.getRegion(), "unknown"),
                        LinkedHashMap::new,
                        Collectors.counting()));
        Map<String, Long> cities = active.stream()
                .collect(Collectors.groupingBy(
                        residence -> Objects.toString(residence.getCity(), "未设置"),
                        LinkedHashMap::new,
                        Collectors.counting()));
        LocalDateTime lastUpdated = active.stream()
                .map(Residence::getUpdateTime)
                .filter(Objects::nonNull)
                .max(Comparator.naturalOrder())
                .orElse(null);
        return new ResidenceStats(active.size(), cities, regions, lastUpdated);
    }

    @Override
    public List<ResidenceOption> options() {
        return residenceMapper.selectList(new LambdaQueryWrapper<Residence>()
                        .eq(Residence::getActive, 1)
                        .orderByAsc(Residence::getName)).stream()
                .map(item -> new ResidenceOption(
                        item.getId(), item.getSourceId(), item.getName(), item.getCity()))
                .toList();
    }

    @Override
    @Transactional
    public ResidenceImportResult importHtml(MultipartFile file) {
        validateHtml(file);
        String fileName = Objects.requireNonNullElse(file.getOriginalFilename(), "residences.html");
        if (fileName.length() > 255) {
            fileName = fileName.substring(fileName.length() - 255);
        }
        Path temporaryFile = null;
        try {
            byte[] bytes = file.getBytes();
            String sourceHash = sha256(bytes);
            temporaryFile = Files.createTempFile("residence-import-", ".html");
            Files.write(temporaryFile, bytes);
            List<ResidenceSourceData> sourceRows =
                    residenceHtmlDocumentReader.readResidenceData(temporaryFile);
            ensureUniqueSourceIds(sourceRows);

            Map<String, Residence> existingBySourceId = residenceMapper.selectList(null).stream()
                    .collect(Collectors.toMap(
                            Residence::getSourceId,
                            Function.identity(),
                            (first, ignored) -> first,
                            LinkedHashMap::new));
            Set<String> importedIds = new LinkedHashSet<>();
            int inserted = 0;
            int updated = 0;
            int unchanged = 0;

            for (ResidenceSourceData source : sourceRows) {
                importedIds.add(source.sourceId());
                Residence residence = existingBySourceId.get(source.sourceId());
                if (residence == null) {
                    residence = new Residence();
                    residence.setSourceId(source.sourceId());
                    applySource(residence, source, fileName, sourceHash);
                    residenceMapper.insert(residence);
                    inserted++;
                    continue;
                }

                Residence proposed = new Residence();
                proposed.setSourceId(source.sourceId());
                applySource(proposed, source, fileName, sourceHash);
                if (sameImportedFields(residence, proposed)) {
                    unchanged++;
                    continue;
                }
                applySource(residence, source, fileName, sourceHash);
                residenceMapper.updateById(residence);
                updated++;
            }

            int deactivated = deactivateMissing(importedIds);
            return new ResidenceImportResult(sourceRows.size(), inserted, updated,
                    unchanged, deactivated, fileName);
        }
        catch (IllegalArgumentException ex) {
            throw new BusinessException(ex.getMessage());
        }
        catch (IOException ex) {
            throw new BusinessException("读取 HTML 文件失败");
        }
        finally {
            if (temporaryFile != null) {
                try {
                    Files.deleteIfExists(temporaryFile);
                }
                catch (IOException ignored) {
                    // 临时文件由操作系统临时目录兜底清理，不影响已完成的数据库事务。
                }
            }
        }
    }

    private int deactivateMissing(Set<String> importedIds) {
        LambdaUpdateWrapper<Residence> wrapper = new LambdaUpdateWrapper<Residence>()
                .eq(Residence::getActive, 1)
                .isNotNull(Residence::getSourceHash)
                .set(Residence::getActive, 0);
        if (!importedIds.isEmpty()) {
            wrapper.notIn(Residence::getSourceId, importedIds);
        }
        return residenceMapper.update(null, wrapper);
    }

    private static void applySource(Residence target, ResidenceSourceData source,
                                    String fileName, String sourceHash) {
        target.setName(source.name());
        target.setCity(DEFAULT_CITY);
        target.setRegion(blankToNull(source.region()));
        target.setZone(blankToNull(source.zone()));
        target.setAddress(Objects.requireNonNullElse(source.address(), ""));
        target.setStation(blankToNull(source.station()));
        target.setLatitude(decimal(source.latitude()));
        target.setLongitude(decimal(source.longitude()));
        target.setMapUrl(blankToNull(source.mapUrl()));
        target.setSourceFileName(fileName);
        target.setSourceHash(sourceHash);
        target.setActive(1);
    }

    private static boolean sameImportedFields(Residence current, Residence proposed) {
        return Objects.equals(current.getName(), proposed.getName())
                && Objects.equals(current.getCity(), proposed.getCity())
                && Objects.equals(current.getRegion(), proposed.getRegion())
                && Objects.equals(current.getZone(), proposed.getZone())
                && Objects.equals(current.getAddress(), proposed.getAddress())
                && Objects.equals(current.getStation(), proposed.getStation())
                && sameDecimal(current.getLatitude(), proposed.getLatitude())
                && sameDecimal(current.getLongitude(), proposed.getLongitude())
                && Objects.equals(current.getMapUrl(), proposed.getMapUrl())
                && Objects.equals(current.getSourceFileName(), proposed.getSourceFileName())
                && Objects.equals(current.getSourceHash(), proposed.getSourceHash())
                && Objects.equals(current.getActive(), 1);
    }

    private static boolean sameDecimal(BigDecimal first, BigDecimal second) {
        if (first == null || second == null) {
            return first == second;
        }
        return first.compareTo(second) == 0;
    }

    private static BigDecimal decimal(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return new BigDecimal(value);
        }
        catch (NumberFormatException ex) {
            throw new BusinessException("HTML 中包含无效经纬度：" + value);
        }
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static String normalizedRegion(String value) {
        return value == null || value.isBlank() ? null : value.trim().toLowerCase(Locale.ROOT);
    }

    private static void validateCoordinates(BigDecimal latitude, BigDecimal longitude) {
        if (latitude != null
                && (latitude.compareTo(BigDecimal.valueOf(-90)) < 0
                || latitude.compareTo(BigDecimal.valueOf(90)) > 0)) {
            throw new BusinessException("纬度必须在 -90 到 90 之间");
        }
        if (longitude != null
                && (longitude.compareTo(BigDecimal.valueOf(-180)) < 0
                || longitude.compareTo(BigDecimal.valueOf(180)) > 0)) {
            throw new BusinessException("经度必须在 -180 到 180 之间");
        }
    }

    private static void ensureUniqueSourceIds(List<ResidenceSourceData> rows) {
        Set<String> ids = new LinkedHashSet<>();
        for (ResidenceSourceData row : rows) {
            if (row.sourceId() == null || row.sourceId().isBlank() || !ids.add(row.sourceId())) {
                throw new BusinessException("HTML 中存在空或重复的公寓标识");
            }
        }
    }

    private static void validateHtml(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new BusinessException("请选择 HTML 文件");
        }
        String fileName = Objects.requireNonNullElse(file.getOriginalFilename(), "");
        String normalized = fileName.toLowerCase(Locale.ROOT);
        if (!normalized.endsWith(".html") && !normalized.endsWith(".htm")) {
            throw new BusinessException("公寓地址数据源必须是 HTML 文件");
        }
        if (file.getSize() > MAX_HTML_SIZE) {
            throw new BusinessException("HTML 文件不能超过 10MB");
        }
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(bytes));
        }
        catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is unavailable", ex);
        }
    }
}
