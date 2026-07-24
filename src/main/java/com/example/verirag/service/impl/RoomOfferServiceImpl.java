package com.example.verirag.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.example.verirag.common.PageResult;
import com.example.verirag.dto.RoomOfferImportResult;
import com.example.verirag.dto.RoomOfferSaveRequest;
import com.example.verirag.dto.RoomOfferStats;
import com.example.verirag.dto.RoomOfferView;
import com.example.verirag.dto.RoomOfferWorkbookData;
import com.example.verirag.dto.RoomPriceTierRequest;
import com.example.verirag.entity.OfferImportBatch;
import com.example.verirag.entity.Residence;
import com.example.verirag.entity.RoomInventory;
import com.example.verirag.entity.RoomPriceTier;
import com.example.verirag.exception.BusinessException;
import com.example.verirag.mapper.OfferImportBatchMapper;
import com.example.verirag.mapper.ResidenceMapper;
import com.example.verirag.mapper.RoomInventoryMapper;
import com.example.verirag.mapper.RoomPriceTierMapper;
import com.example.verirag.service.RoomOfferService;
import com.example.verirag.util.RoomOfferWorkbookReader;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.math.BigDecimal;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
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
public class RoomOfferServiceImpl implements RoomOfferService {

    private static final long MAX_IMPORT_SIZE = 20 * 1024 * 1024;
    private static final Set<String> STATUSES =
            Set.of("AVAILABLE", "LIMITED", "SOLD_OUT", "UNKNOWN");
    private static final Set<String> ROOT_TYPES =
            Set.of("Studio", "Ensuite", "Non-Ensuite", "Twin Studio", "Apartment", "Other");
    private static final Set<String> CURRENCIES = Set.of("GBP", "EUR", "CNY");

    private final RoomInventoryMapper inventoryMapper;
    private final RoomPriceTierMapper priceTierMapper;
    private final OfferImportBatchMapper importBatchMapper;
    private final ResidenceMapper residenceMapper;
    private final RoomOfferWorkbookReader workbookReader;

    @Override
    public PageResult<RoomOfferView> page(String keyword, Long residenceId, String status,
                                          int page, int size) {
        int safePage = Math.max(page, 1);
        int safeSize = Math.min(Math.max(size, 1), 100);
        String query = trim(keyword);
        String normalizedStatus = trim(status).toUpperCase(Locale.ROOT);
        List<Long> matchingResidenceIds = query.isBlank() ? List.of()
                : residenceMapper.selectList(new LambdaQueryWrapper<Residence>()
                        .eq(Residence::getActive, 1)
                        .like(Residence::getName, query)).stream()
                .map(Residence::getId).toList();

        LambdaQueryWrapper<RoomInventory> wrapper = new LambdaQueryWrapper<RoomInventory>()
                .eq(residenceId != null, RoomInventory::getResidenceId, residenceId)
                .eq(!normalizedStatus.isBlank(), RoomInventory::getInventoryStatus, normalizedStatus)
                .orderByDesc(RoomInventory::getInventoryUpdatedAt)
                .orderByAsc(RoomInventory::getRoomName);
        if (!query.isBlank()) {
            wrapper.and(condition -> {
                condition.like(RoomInventory::getRoomName, query)
                        .or().like(RoomInventory::getRoomCode, query);
                if (!matchingResidenceIds.isEmpty()) {
                    condition.or().in(RoomInventory::getResidenceId, matchingResidenceIds);
                }
            });
        }
        Page<RoomInventory> result =
                inventoryMapper.selectPage(new Page<>(safePage, safeSize), wrapper);
        return PageResult.of(result.getTotal(), toViews(result.getRecords()));
    }

    @Override
    public RoomOfferView get(Long id) {
        RoomInventory inventory = requireInventory(id);
        return toViews(List.of(inventory)).getFirst();
    }

    @Override
    public RoomOfferStats stats() {
        List<RoomInventory> rows = inventoryMapper.selectList(null);
        Map<String, Long> counts = rows.stream().collect(Collectors.groupingBy(
                RoomInventory::getInventoryStatus, Collectors.counting()));
        return new RoomOfferStats(
                rows.size(),
                counts.getOrDefault("AVAILABLE", 0L),
                counts.getOrDefault("LIMITED", 0L),
                counts.getOrDefault("SOLD_OUT", 0L),
                counts.getOrDefault("UNKNOWN", 0L));
    }

    @Override
    @Transactional
    public void save(RoomOfferSaveRequest request) {
        Residence residence = requireActiveResidence(request.residenceId());
        validateInventory(
                request.roomCode(), request.roomName(), request.rootType(),
                request.earliestStartDate(), request.latestEndDate(),
                request.remainingQuantity(), request.inventoryStatus(),
                request.inventoryUpdatedAt());
        List<RoomPriceTierRequest> tiers = validateTierRequests(request.priceTiers());
        ensureUniqueScope(request.id(), residence.getId(), request.roomCode(),
                request.earliestStartDate(), request.latestEndDate());

        RoomInventory inventory = request.id() == null
                ? new RoomInventory() : requireInventory(request.id());
        inventory.setResidenceId(residence.getId());
        inventory.setRoomCode(trim(request.roomCode()));
        inventory.setRoomName(trim(request.roomName()));
        inventory.setRootType(trim(request.rootType()));
        inventory.setEarliestStartDate(request.earliestStartDate());
        inventory.setLatestEndDate(request.latestEndDate());
        inventory.setRemainingQuantity(request.remainingQuantity());
        inventory.setInventoryStatus(request.inventoryStatus().trim().toUpperCase(Locale.ROOT));
        inventory.setInventoryUpdatedAt(request.inventoryUpdatedAt());
        inventory.setNote(trimToNull(request.note()));
        if (request.id() == null) {
            inventoryMapper.insert(inventory);
        }
        else {
            inventoryMapper.updateById(inventory);
            priceTierMapper.delete(new LambdaQueryWrapper<RoomPriceTier>()
                    .eq(RoomPriceTier::getInventoryId, inventory.getId()));
        }
        for (RoomPriceTierRequest tierRequest : tiers) {
            RoomPriceTier tier = new RoomPriceTier();
            tier.setInventoryId(inventory.getId());
            applyTier(tier, tierRequest);
            priceTierMapper.insert(tier);
        }
    }

    @Override
    @Transactional
    public void delete(Long id) {
        requireInventory(id);
        inventoryMapper.deleteById(id);
    }

    @Override
    @Transactional
    public RoomOfferImportResult importWorkbook(MultipartFile file, Long uploadUserId) {
        validateImportFile(file);
        byte[] bytes;
        RoomOfferWorkbookData data;
        try {
            bytes = file.getBytes();
            data = workbookReader.read(file.getInputStream());
        }
        catch (IOException ex) {
            throw new BusinessException("读取 XLSX 文件失败");
        }
        String fileName = safeFileName(file.getOriginalFilename());
        List<Residence> residences = residenceMapper.selectList(
                new LambdaQueryWrapper<Residence>().eq(Residence::getActive, 1));
        ResidenceResolver resolver = new ResidenceResolver(residences);
        List<String> warnings = new ArrayList<>();

        OfferImportBatch batch = new OfferImportBatch();
        batch.setFileName(fileName);
        batch.setFileHash(sha256(bytes));
        batch.setStatus("SUCCESS");
        batch.setInventoryTotal(data.inventories().size());
        batch.setInventoryInserted(0);
        batch.setInventoryUpdated(0);
        batch.setPriceTotal(data.prices().size());
        batch.setPriceInserted(0);
        batch.setPriceUpdated(0);
        batch.setSkipped(0);
        batch.setUploadUserId(uploadUserId);
        importBatchMapper.insert(batch);

        Map<InventoryBusinessKey, RoomInventory> inventoryByKey = new LinkedHashMap<>();
        for (RoomOfferWorkbookData.InventoryRow row : data.inventories()) {
            Residence residence = resolver.resolve(
                    row.residenceSourceId(), row.residenceName(), row.rowNumber(), warnings);
            validateInventory(
                    row.roomCode(), row.roomName(), row.rootType(),
                    row.earliestStartDate(), row.latestEndDate(),
                    row.remainingQuantity(), row.inventoryStatus(),
                    row.inventoryUpdatedAt());
            InventoryBusinessKey key = new InventoryBusinessKey(
                    residence.getId(), trim(row.roomCode()),
                    row.earliestStartDate(), row.latestEndDate());
            RoomInventory existing = findInventory(key);
            if (existing != null && !row.inventoryUpdatedAt()
                    .isAfter(existing.getInventoryUpdatedAt())) {
                batch.setSkipped(batch.getSkipped() + 1);
                inventoryByKey.put(key, existing);
                continue;
            }
            RoomInventory target = existing == null ? new RoomInventory() : existing;
            target.setResidenceId(residence.getId());
            target.setRoomCode(trim(row.roomCode()));
            target.setRoomName(trim(row.roomName()));
            target.setRootType(trim(row.rootType()));
            target.setEarliestStartDate(row.earliestStartDate());
            target.setLatestEndDate(row.latestEndDate());
            target.setRemainingQuantity(row.remainingQuantity());
            target.setInventoryStatus(row.inventoryStatus().trim().toUpperCase(Locale.ROOT));
            target.setInventoryUpdatedAt(row.inventoryUpdatedAt());
            target.setNote(trimToNull(row.note()));
            target.setSourceFileName(fileName);
            target.setImportBatchId(batch.getId());
            if (existing == null) {
                inventoryMapper.insert(target);
                batch.setInventoryInserted(batch.getInventoryInserted() + 1);
            }
            else {
                inventoryMapper.updateById(target);
                batch.setInventoryUpdated(batch.getInventoryUpdated() + 1);
            }
            inventoryByKey.put(key, target);
        }

        List<ResolvedPriceRow> resolvedPrices = new ArrayList<>();
        for (RoomOfferWorkbookData.PriceRow row : data.prices()) {
            Residence residence = resolver.resolve(
                    row.residenceSourceId(), row.residenceName(), row.rowNumber(), warnings);
            validatePriceRow(row);
            InventoryBusinessKey key = new InventoryBusinessKey(
                    residence.getId(), trim(row.roomCode()),
                    row.earliestStartDate(), row.latestEndDate());
            RoomInventory inventory = inventoryByKey.computeIfAbsent(key, this::findInventory);
            if (inventory == null) {
                throw new BusinessException("租期价格导入第" + row.rowNumber()
                        + "行找不到对应库存记录，请先在“房型库存导入”填写相同公寓、房型编码和日期");
            }
            resolvedPrices.add(new ResolvedPriceRow(inventory, row));
        }
        validateImportedPriceGroups(resolvedPrices);

        Set<Long> affectedInventoryIds = new LinkedHashSet<>();
        for (ResolvedPriceRow resolved : resolvedPrices) {
            RoomOfferWorkbookData.PriceRow row = resolved.row();
            RoomInventory inventory = resolved.inventory();
            affectedInventoryIds.add(inventory.getId());
            RoomPriceTier existing = priceTierMapper.selectOne(
                    new LambdaQueryWrapper<RoomPriceTier>()
                            .eq(RoomPriceTier::getInventoryId, inventory.getId())
                            .eq(RoomPriceTier::getMinWeeks, row.minWeeks())
                            .last("LIMIT 1"));
            if (existing != null && !row.priceUpdatedAt().isAfter(existing.getPriceUpdatedAt())) {
                batch.setSkipped(batch.getSkipped() + 1);
                continue;
            }
            RoomPriceTier target = existing == null ? new RoomPriceTier() : existing;
            target.setInventoryId(inventory.getId());
            target.setMinWeeks(row.minWeeks());
            target.setMaxWeeks(row.maxWeeks());
            target.setWeeklyPrice(row.weeklyPrice());
            target.setCurrency(row.currency().trim().toUpperCase(Locale.ROOT));
            target.setPriceUpdatedAt(row.priceUpdatedAt());
            target.setNote(trimToNull(row.note()));
            target.setSourceFileName(fileName);
            target.setImportBatchId(batch.getId());
            if (existing == null) {
                priceTierMapper.insert(target);
                batch.setPriceInserted(batch.getPriceInserted() + 1);
            }
            else {
                priceTierMapper.updateById(target);
                batch.setPriceUpdated(batch.getPriceUpdated() + 1);
            }
        }
        for (Long inventoryId : affectedInventoryIds) {
            validatePersistedTiers(inventoryId);
        }

        batch.setFinishTime(LocalDateTime.now());
        batch.setMessage("导入成功：库存新增" + batch.getInventoryInserted()
                + "、更新" + batch.getInventoryUpdated()
                + "；价格新增" + batch.getPriceInserted()
                + "、更新" + batch.getPriceUpdated()
                + "；跳过" + batch.getSkipped());
        importBatchMapper.updateById(batch);
        return new RoomOfferImportResult(
                batch.getId(),
                batch.getInventoryTotal(),
                batch.getInventoryInserted(),
                batch.getInventoryUpdated(),
                batch.getPriceTotal(),
                batch.getPriceInserted(),
                batch.getPriceUpdated(),
                batch.getSkipped(),
                warnings.stream().distinct().toList());
    }

    @Override
    public List<OfferImportBatch> recentImports(int limit) {
        int safeLimit = Math.min(Math.max(limit, 1), 20);
        return importBatchMapper.selectList(new LambdaQueryWrapper<OfferImportBatch>()
                .orderByDesc(OfferImportBatch::getCreateTime)
                .last("LIMIT " + safeLimit));
    }

    private List<RoomOfferView> toViews(List<RoomInventory> inventories) {
        if (inventories.isEmpty()) {
            return List.of();
        }
        Set<Long> residenceIds = inventories.stream()
                .map(RoomInventory::getResidenceId).collect(Collectors.toSet());
        Map<Long, Residence> residences = residenceMapper.selectBatchIds(residenceIds).stream()
                .collect(Collectors.toMap(Residence::getId, Function.identity()));
        Set<Long> inventoryIds = inventories.stream()
                .map(RoomInventory::getId).collect(Collectors.toSet());
        Map<Long, List<RoomPriceTier>> prices = priceTierMapper.selectList(
                        new LambdaQueryWrapper<RoomPriceTier>()
                                .in(RoomPriceTier::getInventoryId, inventoryIds)
                                .orderByAsc(RoomPriceTier::getMinWeeks)).stream()
                .collect(Collectors.groupingBy(
                        RoomPriceTier::getInventoryId, LinkedHashMap::new, Collectors.toList()));
        return inventories.stream().map(inventory -> {
            Residence residence = residences.get(inventory.getResidenceId());
            return new RoomOfferView(
                    inventory.getId(),
                    inventory.getResidenceId(),
                    residence == null ? null : residence.getSourceId(),
                    residence == null ? "已删除公寓" : residence.getName(),
                    inventory.getRoomCode(),
                    inventory.getRoomName(),
                    inventory.getRootType(),
                    inventory.getEarliestStartDate(),
                    inventory.getLatestEndDate(),
                    inventory.getRemainingQuantity(),
                    inventory.getInventoryStatus(),
                    inventory.getInventoryUpdatedAt(),
                    inventory.getNote(),
                    inventory.getSourceFileName(),
                    inventory.getCreateTime(),
                    inventory.getUpdateTime(),
                    prices.getOrDefault(inventory.getId(), List.of()));
        }).toList();
    }

    private void validateInventory(String roomCode, String roomName, String rootType,
                                   LocalDate start, LocalDate end, Integer quantity,
                                   String status, LocalDateTime updatedAt) {
        if (!StringUtils.hasText(roomCode) || !StringUtils.hasText(roomName)) {
            throw new BusinessException("房型编码和房型名称不能为空");
        }
        if (!ROOT_TYPES.contains(trim(rootType))) {
            throw new BusinessException("不支持的 Root Type：" + rootType);
        }
        if (start == null || end == null || end.isBefore(start)) {
            throw new BusinessException("最晚退房日期不能早于最早起租日期");
        }
        if (quantity != null && quantity < 0) {
            throw new BusinessException("剩余数量不能小于0");
        }
        String normalizedStatus = trim(status).toUpperCase(Locale.ROOT);
        if (!STATUSES.contains(normalizedStatus)) {
            throw new BusinessException("不支持的库存状态：" + status);
        }
        if (quantity != null && quantity == 0 && !"SOLD_OUT".equals(normalizedStatus)) {
            throw new BusinessException("剩余数量为0时库存状态必须为SOLD_OUT");
        }
        if (quantity != null && quantity > 0 && "SOLD_OUT".equals(normalizedStatus)) {
            throw new BusinessException("库存状态为SOLD_OUT时剩余数量必须为0");
        }
        if (updatedAt == null) {
            throw new BusinessException("库存更新时间不能为空");
        }
    }

    private List<RoomPriceTierRequest> validateTierRequests(List<RoomPriceTierRequest> requests) {
        if (requests == null || requests.isEmpty()) {
            throw new BusinessException("至少填写一个价格档位");
        }
        List<RoomPriceTierRequest> sorted = requests.stream()
                .sorted(Comparator.comparing(RoomPriceTierRequest::minWeeks)).toList();
        Set<Integer> minimums = new HashSet<>();
        for (RoomPriceTierRequest tier : sorted) {
            if (!minimums.add(tier.minWeeks())) {
                throw new BusinessException("价格档位的最短租期不能重复");
            }
            validateTier(tier.minWeeks(), tier.maxWeeks(), tier.weeklyPrice(),
                    tier.currency(), tier.priceUpdatedAt());
        }
        validateTierOrder(sorted.stream().map(tier -> new TierValue(
                tier.minWeeks(), tier.maxWeeks(), tier.weeklyPrice())).toList());
        return sorted;
    }

    private void validatePriceRow(RoomOfferWorkbookData.PriceRow row) {
        validateTier(row.minWeeks(), row.maxWeeks(), row.weeklyPrice(),
                row.currency(), row.priceUpdatedAt());
        if (row.earliestStartDate() == null || row.latestEndDate() == null
                || row.latestEndDate().isBefore(row.earliestStartDate())) {
            throw new BusinessException("租期价格导入第" + row.rowNumber() + "行日期范围无效");
        }
    }

    private void validateTier(Integer minWeeks, Integer maxWeeks, BigDecimal price,
                              String currency, LocalDateTime updatedAt) {
        if (minWeeks == null || minWeeks < 1 || minWeeks > 104) {
            throw new BusinessException("最短租期必须是1至104周");
        }
        if (maxWeeks != null && (maxWeeks < minWeeks || maxWeeks > 104)) {
            throw new BusinessException("最长租期必须大于等于最短租期且不超过104周");
        }
        if (price == null || price.compareTo(BigDecimal.ZERO) <= 0) {
            throw new BusinessException("每周价格必须大于0");
        }
        String normalizedCurrency = trim(currency).toUpperCase(Locale.ROOT);
        if (!CURRENCIES.contains(normalizedCurrency)) {
            throw new BusinessException("不支持的币种：" + currency);
        }
        if (updatedAt == null) {
            throw new BusinessException("价格更新时间不能为空");
        }
    }

    private void validateTierOrder(List<TierValue> tiers) {
        List<TierValue> sorted = tiers.stream()
                .sorted(Comparator.comparing(TierValue::minWeeks)).toList();
        for (int index = 0; index < sorted.size(); index++) {
            TierValue current = sorted.get(index);
            if (index == 0) {
                continue;
            }
            TierValue previous = sorted.get(index - 1);
            if (previous.maxWeeks() == null || current.minWeeks() <= previous.maxWeeks()) {
                throw new BusinessException("价格档位区间不能重叠，开放档位必须放在最后");
            }
            if (current.weeklyPrice().compareTo(previous.weeklyPrice()) > 0) {
                throw new BusinessException("租期越长时每周价格不能更高");
            }
        }
    }

    private void validateImportedPriceGroups(List<ResolvedPriceRow> rows) {
        Map<Long, List<TierValue>> grouped = rows.stream().collect(Collectors.groupingBy(
                item -> item.inventory().getId(),
                Collectors.mapping(item -> new TierValue(
                        item.row().minWeeks(), item.row().maxWeeks(),
                        item.row().weeklyPrice()), Collectors.toList())));
        grouped.values().forEach(this::validateTierOrder);
    }

    private void validatePersistedTiers(Long inventoryId) {
        List<TierValue> tiers = priceTierMapper.selectList(
                        new LambdaQueryWrapper<RoomPriceTier>()
                                .eq(RoomPriceTier::getInventoryId, inventoryId)
                                .orderByAsc(RoomPriceTier::getMinWeeks)).stream()
                .map(tier -> new TierValue(
                        tier.getMinWeeks(), tier.getMaxWeeks(), tier.getWeeklyPrice()))
                .toList();
        validateTierOrder(tiers);
    }

    private void applyTier(RoomPriceTier target, RoomPriceTierRequest source) {
        target.setMinWeeks(source.minWeeks());
        target.setMaxWeeks(source.maxWeeks());
        target.setWeeklyPrice(source.weeklyPrice());
        target.setCurrency(source.currency().trim().toUpperCase(Locale.ROOT));
        target.setPriceUpdatedAt(source.priceUpdatedAt());
        target.setNote(trimToNull(source.note()));
    }

    private void ensureUniqueScope(Long currentId, Long residenceId, String roomCode,
                                   LocalDate start, LocalDate end) {
        LambdaQueryWrapper<RoomInventory> wrapper = new LambdaQueryWrapper<RoomInventory>()
                .eq(RoomInventory::getResidenceId, residenceId)
                .eq(RoomInventory::getRoomCode, trim(roomCode))
                .eq(RoomInventory::getEarliestStartDate, start)
                .eq(RoomInventory::getLatestEndDate, end)
                .ne(currentId != null, RoomInventory::getId, currentId);
        if (inventoryMapper.selectCount(wrapper) > 0) {
            throw new BusinessException("相同公寓、房型编码和日期范围的库存记录已存在");
        }
    }

    private RoomInventory findInventory(InventoryBusinessKey key) {
        return inventoryMapper.selectOne(new LambdaQueryWrapper<RoomInventory>()
                .eq(RoomInventory::getResidenceId, key.residenceId())
                .eq(RoomInventory::getRoomCode, key.roomCode())
                .eq(RoomInventory::getEarliestStartDate, key.start())
                .eq(RoomInventory::getLatestEndDate, key.end())
                .last("LIMIT 1"));
    }

    private RoomInventory requireInventory(Long id) {
        if (id == null) {
            throw new BusinessException("房型库存ID不能为空");
        }
        RoomInventory inventory = inventoryMapper.selectById(id);
        if (inventory == null) {
            throw new BusinessException(404, "房型库存不存在");
        }
        return inventory;
    }

    private Residence requireActiveResidence(Long id) {
        Residence residence = residenceMapper.selectById(id);
        if (residence == null || !Objects.equals(residence.getActive(), 1)) {
            throw new BusinessException("公寓不存在或已停用");
        }
        return residence;
    }

    private void validateImportFile(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new BusinessException("请选择结构化 XLSX 文件");
        }
        String name = Objects.requireNonNullElse(file.getOriginalFilename(), "");
        if (!name.toLowerCase(Locale.ROOT).endsWith(".xlsx")) {
            throw new BusinessException("批量导入仅支持 .xlsx 文件");
        }
        if (file.getSize() > MAX_IMPORT_SIZE) {
            throw new BusinessException("导入文件不能超过20MB");
        }
    }

    private String safeFileName(String fileName) {
        String value = Objects.requireNonNullElse(fileName, "结构化模板.xlsx");
        return value.length() <= 255 ? value : value.substring(value.length() - 255);
    }

    private String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(bytes));
        }
        catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 unavailable", ex);
        }
    }

    private String trim(String value) {
        return value == null ? "" : value.trim();
    }

    private String trimToNull(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }

    private record InventoryBusinessKey(
            Long residenceId, String roomCode, LocalDate start, LocalDate end) {
    }

    private record TierValue(Integer minWeeks, Integer maxWeeks, BigDecimal weeklyPrice) {
    }

    private record ResolvedPriceRow(
            RoomInventory inventory, RoomOfferWorkbookData.PriceRow row) {
    }

    private static final class ResidenceResolver {
        private final Map<String, Residence> bySourceId;
        private final List<Residence> residences;

        private ResidenceResolver(List<Residence> residences) {
            this.residences = residences;
            this.bySourceId = residences.stream().collect(Collectors.toMap(
                    residence -> residence.getSourceId().toLowerCase(Locale.ROOT),
                    Function.identity()));
        }

        private Residence resolve(String sourceId, String name, int rowNumber,
                                  List<String> warnings) {
            if (StringUtils.hasText(sourceId)) {
                Residence residence = bySourceId.get(sourceId.trim().toLowerCase(Locale.ROOT));
                if (residence == null) {
                    throw new BusinessException("第" + rowNumber
                            + "行公寓编码不存在：" + sourceId);
                }
                if (StringUtils.hasText(name)
                        && !normalize(name).equals(normalize(residence.getName()))) {
                    warnings.add("公寓编码" + sourceId + "对应名称已统一为“"
                            + residence.getName() + "”（源名称：" + name + "）");
                }
                return residence;
            }
            if (!StringUtils.hasText(name)) {
                throw new BusinessException("第" + rowNumber + "行公寓编码和名称不能同时为空");
            }
            String normalized = normalize(name);
            List<Residence> exact = residences.stream()
                    .filter(item -> normalize(item.getName()).equals(normalized)).toList();
            if (exact.size() == 1) {
                return exact.getFirst();
            }
            List<ScoredResidence> scored = residences.stream()
                    .map(item -> new ScoredResidence(item, similarity(normalized,
                            normalize(item.getName()))))
                    .sorted(Comparator.comparing(ScoredResidence::score).reversed()).toList();
            if (!scored.isEmpty() && scored.getFirst().score() >= 0.82
                    && (scored.size() == 1
                    || scored.getFirst().score() - scored.get(1).score() >= 0.05)) {
                Residence matched = scored.getFirst().residence();
                warnings.add("公寓名称“" + name + "”已近似匹配为“" + matched.getName() + "”");
                return matched;
            }
            throw new BusinessException("第" + rowNumber
                    + "行公寓名称无法唯一匹配到地址库：" + name);
        }

        private static String normalize(String value) {
            return value == null ? "" : value.toLowerCase(Locale.ROOT)
                    .replace("&", " and ")
                    .replaceAll("[^a-z0-9]+", " ")
                    .trim().replaceAll("\\s+", " ");
        }

        private static double similarity(String first, String second) {
            int denominator = Math.max(first.length(), second.length());
            return denominator == 0 ? 1 : 1 - (double) distance(first, second) / denominator;
        }

        private static int distance(String first, String second) {
            int[] row = new int[second.length() + 1];
            for (int j = 0; j <= second.length(); j++) {
                row[j] = j;
            }
            for (int i = 1; i <= first.length(); i++) {
                int diagonal = row[0];
                row[0] = i;
                for (int j = 1; j <= second.length(); j++) {
                    int previous = row[j];
                    row[j] = Math.min(
                            Math.min(row[j] + 1, row[j - 1] + 1),
                            diagonal + (first.charAt(i - 1) == second.charAt(j - 1) ? 0 : 1));
                    diagonal = previous;
                }
            }
            return row[second.length()];
        }

        private record ScoredResidence(Residence residence, double score) {
        }
    }
}
