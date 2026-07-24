package com.example.verirag.tool;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.example.verirag.entity.Residence;
import com.example.verirag.entity.ResidenceDetail;
import com.example.verirag.entity.ResidenceNearbyPlace;
import com.example.verirag.entity.RoomInventory;
import com.example.verirag.entity.RoomPriceTier;
import com.example.verirag.mapper.ResidenceDetailMapper;
import com.example.verirag.mapper.ResidenceMapper;
import com.example.verirag.mapper.ResidenceNearbyPlaceMapper;
import com.example.verirag.mapper.RoomInventoryMapper;
import com.example.verirag.mapper.RoomPriceTierMapper;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.Normalizer;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.StringJoiner;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * 面向模型的只读房源工具。所有日期、租期、价格和库存判断都在服务端完成，
 * 模型只负责收集条件和组织结果。
 */
@Component
@RequiredArgsConstructor
public class PropertyQueryTools {

    private static final Logger log = LoggerFactory.getLogger(PropertyQueryTools.class);
    private static final int DEFAULT_RESIDENCE_LIMIT = 4;
    private static final int MAX_RESIDENCE_LIMIT = 4;
    private static final int MAX_ROOMS_PER_RESIDENCE = 2;

    private final ResidenceMapper residenceMapper;
    private final ResidenceDetailMapper residenceDetailMapper;
    private final ResidenceNearbyPlaceMapper nearbyPlaceMapper;
    private final RoomInventoryMapper inventoryMapper;
    private final RoomPriceTierMapper priceTierMapper;

    @Tool(name = "search_room_offers", description = """
            查询结构化公寓房型、入住时间、租期价格和库存。适用于找房、报价、
            可预订状态和指定城市/公寓/房型筛选。startDateFrom/startDateTo 表示
            可接受的起租日期窗口，格式 YYYY-MM-DD；stayWeeks 是实际租住周数。
            nearbyPlaceKeyword 可直接按学校或地标筛选，maxTravelMinutes 限制资料中
            明确给出的最长通勤时间。结果按不同公寓分组，并返回匹配地点证据。
            residenceNames 仅用于用户明确指定一组公寓时的硬限制。本工具每次最多
            返回4个公寓、每个公寓最多2个房型；结果不足或为空时不要再次调用。
            """)
    public RoomOfferSearchResult searchRoomOffers(
            @ToolParam(description = "城市，例如 London、Manchester；不限制时留空",
                    required = false)
            String city,
            @ToolParam(description = "公寓名称、编码、地址或车站关键词；不能填写学校名称",
                    required = false)
            String residenceKeyword,
            @ToolParam(description = "用户明确指定的候选公寓名称列表，用逗号分隔；未指定时留空",
                    required = false)
            String residenceNames,
            @ToolParam(description = "附近学校或地标名称，例如 UCL、LSE、King's College London；没有地点条件时留空",
                    required = false)
            String nearbyPlaceKeyword,
            @ToolParam(description = "允许的最长通勤分钟；查询“附近”时通常传25，仅使用数据库中明确的通勤时间",
                    required = false)
            Integer maxTravelMinutes,
            @ToolParam(description = "可接受的最早起租日，YYYY-MM-DD", required = false)
            String startDateFrom,
            @ToolParam(description = "可接受的最晚起租日，YYYY-MM-DD；只给一个日期时可留空",
                    required = false)
            String startDateTo,
            @ToolParam(description = "租住周数，例如六个月通常传26", required = false)
            Integer stayWeeks,
            @ToolParam(description = "房型大类，可用逗号分隔：Studio, Ensuite, Non-Ensuite, Twin Studio, Apartment",
                    required = false)
            String rootTypes,
            @ToolParam(description = "最高每周预算；币种为GBP", required = false)
            BigDecimal maxWeeklyPrice,
            @ToolParam(description = "是否包含候选公寓中的售罄房型；默认false",
                    required = false)
            Boolean includeSoldOut,
            @ToolParam(description = "最多返回多少个不同公寓，默认4，最大4", required = false)
            Integer limitResidences) {
        return executeTool("search_room_offers",
                toolArguments(
                        "city", city,
                        "residenceKeyword", residenceKeyword,
                        "residenceNames", residenceNames,
                        "nearbyPlaceKeyword", nearbyPlaceKeyword,
                        "maxTravelMinutes", maxTravelMinutes,
                        "startDateFrom", startDateFrom,
                        "startDateTo", startDateTo,
                        "stayWeeks", stayWeeks,
                        "rootTypes", rootTypes,
                        "maxWeeklyPrice", maxWeeklyPrice,
                        "includeSoldOut", includeSoldOut,
                        "limitResidences", limitResidences),
                () -> searchRoomOffersInternal(city, residenceKeyword, residenceNames,
                        nearbyPlaceKeyword, maxTravelMinutes, startDateFrom,
                        startDateTo, stayWeeks, rootTypes, maxWeeklyPrice,
                        includeSoldOut, limitResidences),
                result -> toolArguments(
                        "matchedResidenceCount", result.matchedResidenceCount(),
                        "availableResidenceCount", result.availableResidenceCount(),
                        "soldOutResidenceCount", result.soldOutResidenceCount(),
                        "warningCount", result.warnings().size()));
    }

    private RoomOfferSearchResult searchRoomOffersInternal(
            String city,
            String residenceKeyword,
            String residenceNames,
            String nearbyPlaceKeyword,
            Integer maxTravelMinutes,
            String startDateFrom,
            String startDateTo,
            Integer stayWeeks,
            String rootTypes,
            BigDecimal maxWeeklyPrice,
            Boolean includeSoldOut,
            Integer limitResidences) {
        LocalDate startFrom = parseDate(startDateFrom, "startDateFrom");
        LocalDate startTo = parseDate(startDateTo, "startDateTo");
        if (startFrom == null && startTo != null) {
            startFrom = startTo;
        }
        if (startFrom != null && startTo == null) {
            startTo = startFrom;
        }
        if (startFrom != null && startTo.isBefore(startFrom)) {
            throw new IllegalArgumentException("startDateTo 不能早于 startDateFrom");
        }
        validateWeeks(stayWeeks);
        if (maxWeeklyPrice != null && maxWeeklyPrice.signum() < 0) {
            throw new IllegalArgumentException("maxWeeklyPrice 不能小于0");
        }
        if (maxTravelMinutes != null && (maxTravelMinutes < 1 || maxTravelMinutes > 180)) {
            throw new IllegalArgumentException("maxTravelMinutes 必须在1到180之间");
        }

        int safeLimit = Math.min(Math.max(
                Objects.requireNonNullElse(limitResidences, DEFAULT_RESIDENCE_LIMIT), 1),
                MAX_RESIDENCE_LIMIT);
        boolean withSoldOut = Boolean.TRUE.equals(includeSoldOut);
        Set<String> requestedRootTypes = splitRootTypes(rootTypes);
        List<String> requestedResidenceNames = splitResidenceNames(residenceNames);

        List<Residence> cityResidences = residenceMapper.selectList(
                new LambdaQueryWrapper<Residence>()
                        .eq(Residence::getActive, 1)
                        .orderByAsc(Residence::getCity)
                        .orderByAsc(Residence::getName)).stream()
                .filter(item -> isBlank(city) || equalsIgnoreCase(item.getCity(), city))
                .filter(item -> matchesResidenceKeyword(item, residenceKeyword))
                .filter(item -> matchesResidenceCandidates(item, requestedResidenceNames))
                .toList();
        Map<Long, List<ResidenceNearbyPlace>> nearbyByResidence =
                loadMatchingNearby(cityResidences, nearbyPlaceKeyword, maxTravelMinutes);
        List<Residence> residences = isBlank(nearbyPlaceKeyword)
                ? cityResidences
                : cityResidences.stream()
                        .filter(item -> nearbyByResidence.containsKey(item.getId()))
                        .toList();
        if (residences.isEmpty()) {
            return new RoomOfferSearchResult(city, residenceKeyword, requestedResidenceNames,
                    nearbyPlaceKeyword, maxTravelMinutes,
                    startFrom, startTo,
                    stayWeeks, 0, 0, 0, List.of(),
                    List.of(!isBlank(nearbyPlaceKeyword)
                            ? "数据库中没有找到符合附近地点和通勤时间条件的有效公寓。"
                            : requestedResidenceNames.isEmpty()
                                    ? "没有找到符合城市或公寓关键词的有效公寓。"
                                    : "指定候选公寓在结构化地址库中不存在或当前未启用。"));
        }

        Map<Long, Residence> residenceById = residences.stream()
                .collect(Collectors.toMap(Residence::getId, Function.identity()));
        List<RoomInventory> inventories = inventoryMapper.selectList(
                new LambdaQueryWrapper<RoomInventory>()
                        .in(RoomInventory::getResidenceId, residenceById.keySet()));
        Map<Long, List<RoomPriceTier>> tiersByInventory = loadTiers(inventories);
        LocalDate finalStartFrom = startFrom;
        LocalDate finalStartTo = startTo;

        List<MatchedRoom> matches = inventories.stream()
                .filter(item -> requestedRootTypes.isEmpty()
                        || requestedRootTypes.contains(normalize(item.getRootType())))
                .filter(item -> withSoldOut || !"SOLD_OUT".equals(item.getInventoryStatus()))
                .map(item -> matchRoom(item, tiersByInventory.getOrDefault(item.getId(), List.of()),
                        finalStartFrom, finalStartTo, stayWeeks, maxWeeklyPrice))
                .filter(Objects::nonNull)
                .sorted(roomComparator())
                .toList();

        Map<Long, List<MatchedRoom>> grouped = matches.stream()
                .collect(Collectors.groupingBy(
                        room -> room.inventory().getResidenceId(),
                        LinkedHashMap::new,
                        Collectors.toList()));
        List<ResidenceOfferGroup> groups = grouped.entrySet().stream()
                .map(entry -> toGroup(residenceById.get(entry.getKey()), entry.getValue(),
                        nearbyByResidence.getOrDefault(entry.getKey(), List.of())))
                .sorted(groupComparator(requestedResidenceNames))
                .limit(safeLimit)
                .toList();

        int availableResidences = (int) groups.stream()
                .filter(group -> group.rooms().stream()
                        .anyMatch(room -> "AVAILABLE".equals(room.inventoryStatus())
                                || "LIMITED".equals(room.inventoryStatus())))
                .count();
        int soldOutResidences = (int) groups.stream()
                .filter(group -> group.rooms().stream()
                        .allMatch(room -> "SOLD_OUT".equals(room.inventoryStatus())))
                .count();
        List<String> warnings = new ArrayList<>();
        if (startFrom == null) {
            warnings.add("未提供起租日期，结果没有验证入住时间。");
        }
        if (stayWeeks == null) {
            warnings.add("未提供租期周数，结果返回全部价格档位，未计算确定报价。");
        }
        warnings.add("库存为业务更新时间对应的快照，最终预订前需要再次确认。");
        return new RoomOfferSearchResult(city, residenceKeyword, requestedResidenceNames,
                nearbyPlaceKeyword, maxTravelMinutes,
                startFrom, startTo,
                stayWeeks, groups.size(), availableResidences, soldOutResidences,
                groups, List.copyOf(warnings));
    }

    @Tool(name = "get_residence_details", description = """
            查询一个或多个公寓的结构化详情，包括地址、邮编、车站、交通线路、
            设施、附近学校和附近地标。用于回答指定公寓的介绍、配套、交通和周边问题。
            本工具不返回房型报价或库存。
            """)
    public ResidenceDetailResult getResidenceDetails(
            @ToolParam(description = "公寓名称、编码、地址或车站关键词")
            String keyword,
            @ToolParam(description = "最多返回多少个公寓，默认5，最大10", required = false)
            Integer limit) {
        return executeTool("get_residence_details",
                toolArguments("keyword", keyword, "limit", limit),
                () -> getResidenceDetailsInternal(keyword, limit),
                result -> toolArguments("residenceCount", result.count()));
    }

    private ResidenceDetailResult getResidenceDetailsInternal(String keyword, Integer limit) {
        int safeLimit = Math.min(Math.max(Objects.requireNonNullElse(limit, 5), 1), 10);
        List<Residence> residences = residenceMapper.selectList(
                        new LambdaQueryWrapper<Residence>()
                                .eq(Residence::getActive, 1)
                                .orderByAsc(Residence::getName)).stream()
                .filter(item -> matchesResidenceKeyword(item, keyword))
                .limit(safeLimit)
                .toList();
        if (residences.isEmpty()) {
            return new ResidenceDetailResult(keyword, 0, List.of());
        }
        Map<Long, ResidenceDetail> details = residenceDetailMapper.selectBatchIds(
                        residences.stream().map(Residence::getId).toList()).stream()
                .collect(Collectors.toMap(ResidenceDetail::getResidenceId, Function.identity()));
        Map<Long, List<ResidenceNearbyPlace>> nearby = nearbyPlaceMapper.selectList(
                        new LambdaQueryWrapper<ResidenceNearbyPlace>()
                                .in(ResidenceNearbyPlace::getResidenceId,
                                        residences.stream().map(Residence::getId).toList())
                                .orderByAsc(ResidenceNearbyPlace::getSortOrder)).stream()
                .collect(Collectors.groupingBy(ResidenceNearbyPlace::getResidenceId,
                        LinkedHashMap::new, Collectors.toList()));
        List<ResidenceDetailItem> items = residences.stream()
                .map(residence -> toDetailItem(residence, details.get(residence.getId()),
                        nearby.getOrDefault(residence.getId(), List.of())))
                .toList();
        return new ResidenceDetailResult(keyword, items.size(), items);
    }

    @Tool(name = "quote_room_offer", description = """
            对指定 roomOfferId 做确定性报价。可直接提供 stayWeeks，或同时提供
            startDate 和 endDate（YYYY-MM-DD），系统会按实际天数向上取整为周数，
            匹配正确价格档位并验证日期范围和库存。
            """)
    public RoomOfferQuote quoteRoomOffer(
            @ToolParam(description = "search_room_offers 返回的 roomOfferId")
            Long roomOfferId,
            @ToolParam(description = "入住日 YYYY-MM-DD；只算价格时可留空", required = false)
            String startDate,
            @ToolParam(description = "退房日 YYYY-MM-DD；与入住日成对提供", required = false)
            String endDate,
            @ToolParam(description = "租住周数；与日期同时提供时以日期计算结果为准",
                    required = false)
            Integer stayWeeks) {
        return executeTool("quote_room_offer",
                toolArguments(
                        "roomOfferId", roomOfferId,
                        "startDate", startDate,
                        "endDate", endDate,
                        "stayWeeks", stayWeeks),
                () -> quoteRoomOfferInternal(roomOfferId, startDate, endDate, stayWeeks),
                result -> toolArguments(
                        "roomOfferId", result.roomOfferId(),
                        "residenceName", result.residenceName(),
                        "dateStatus", result.dateStatus(),
                        "available", result.available(),
                        "inventoryStatus", result.inventoryStatus(),
                        "warningCount", result.warnings().size()));
    }

    private RoomOfferQuote quoteRoomOfferInternal(
            Long roomOfferId,
            String startDate,
            String endDate,
            Integer stayWeeks) {
        if (roomOfferId == null) {
            throw new IllegalArgumentException("roomOfferId 不能为空");
        }
        RoomInventory inventory = inventoryMapper.selectById(roomOfferId);
        if (inventory == null) {
            throw new IllegalArgumentException("房型不存在");
        }
        Residence residence = residenceMapper.selectById(inventory.getResidenceId());
        LocalDate start = parseDate(startDate, "startDate");
        LocalDate end = parseDate(endDate, "endDate");
        if ((start == null) != (end == null)) {
            throw new IllegalArgumentException("startDate 和 endDate 必须同时提供");
        }
        List<String> warnings = new ArrayList<>();
        Integer resolvedWeeks = stayWeeks;
        if (start != null) {
            long days = ChronoUnit.DAYS.between(start, end);
            if (days <= 0) {
                throw new IllegalArgumentException("endDate 必须晚于 startDate");
            }
            int dateWeeks = Math.toIntExact((days + 6) / 7);
            if (stayWeeks != null && stayWeeks != dateWeeks) {
                warnings.add("传入周数与起止日期不一致，已采用日期计算的 "
                        + dateWeeks + " 周。");
            }
            resolvedWeeks = dateWeeks;
        }
        validateWeeks(resolvedWeeks);
        if (resolvedWeeks == null) {
            throw new IllegalArgumentException("请提供 stayWeeks，或同时提供 startDate 和 endDate");
        }
        RoomPriceTier tier = findTier(priceTierMapper.selectList(
                new LambdaQueryWrapper<RoomPriceTier>()
                        .eq(RoomPriceTier::getInventoryId, roomOfferId)
                        .orderByAsc(RoomPriceTier::getMinWeeks)), resolvedWeeks);
        if (tier == null) {
            return new RoomOfferQuote(roomOfferId, residenceName(residence),
                    inventory.getRoomName(), start, end, resolvedWeeks,
                    "NO_PRICE_TIER", false, inventory.getInventoryStatus(),
                    inventory.getRemainingQuantity(), null, null, null,
                    inventory.getInventoryUpdatedAt(),
                    List.of("该租期没有对应价格档位，无法报价。"));
        }
        boolean dateMatched = start == null
                || (!start.isBefore(inventory.getEarliestStartDate())
                && !end.isAfter(inventory.getLatestEndDate()));
        if (!dateMatched) {
            warnings.add("请求日期不在该房型可租日期范围内。");
        }
        boolean available = dateMatched
                && ("AVAILABLE".equals(inventory.getInventoryStatus())
                || "LIMITED".equals(inventory.getInventoryStatus()));
        BigDecimal total = tier.getWeeklyPrice()
                .multiply(BigDecimal.valueOf(resolvedWeeks))
                .setScale(2, RoundingMode.HALF_UP);
        warnings.add("库存和报价是数据更新时间对应的快照，最终预订前需要再次确认。");
        return new RoomOfferQuote(roomOfferId, residenceName(residence),
                inventory.getRoomName(), start, end, resolvedWeeks,
                dateMatched ? "MATCHED" : "OUT_OF_RANGE", available,
                inventory.getInventoryStatus(), inventory.getRemainingQuantity(),
                toTier(tier), tier.getWeeklyPrice(), total,
                inventory.getInventoryUpdatedAt(), List.copyOf(warnings));
    }

    @Tool(name = "list_residences", description = """
            查询公寓地址库。可按城市以及公寓名称、编码、地址或车站关键词筛选。
            用于回答某城市有多少公寓、有哪些公寓、地址和最近车站。该工具不判断
            房型库存，也不计算学校或地标通勤距离。
            """)
    public ResidenceListResult listResidences(
            @ToolParam(description = "城市；不限制时留空", required = false)
            String city,
            @ToolParam(description = "公寓名称、编码、地址或车站关键词", required = false)
            String keyword,
            @ToolParam(description = "最多返回数量，默认20，最大100", required = false)
            Integer limit) {
        return executeTool("list_residences",
                toolArguments("city", city, "keyword", keyword, "limit", limit),
                () -> listResidencesInternal(city, keyword, limit),
                result -> toolArguments("residenceCount", result.count()));
    }

    private ResidenceListResult listResidencesInternal(
            String city,
            String keyword,
            Integer limit) {
        int safeLimit = Math.min(Math.max(Objects.requireNonNullElse(limit, 20), 1), 100);
        List<ResidenceItem> items = residenceMapper.selectList(
                        new LambdaQueryWrapper<Residence>()
                                .eq(Residence::getActive, 1)
                                .orderByAsc(Residence::getCity)
                                .orderByAsc(Residence::getName)).stream()
                .filter(item -> isBlank(city) || equalsIgnoreCase(item.getCity(), city))
                .filter(item -> matchesResidenceKeyword(item, keyword))
                .limit(safeLimit)
                .map(PropertyQueryTools::toResidenceItem)
                .toList();
        return new ResidenceListResult(city, keyword, items.size(), items);
    }

    @Tool(name = "get_inventory_summary", description = """
            统计结构化地址库和房型库存。用于回答某城市公寓总数、房型数量、
            可预订、库存紧张、售罄、未知数量及数据覆盖日期。城市留空时返回全部城市。
            """)
    public InventorySummary getInventorySummary(
            @ToolParam(description = "城市；查询全部城市时留空", required = false)
            String city) {
        return executeTool("get_inventory_summary",
                toolArguments("city", city),
                () -> getInventorySummaryInternal(city),
                result -> toolArguments(
                        "residenceCount", result.residenceCount(),
                        "roomOfferCount", result.roomOfferCount(),
                        "statusCount", result.roomOffersByStatus().size()));
    }

    private InventorySummary getInventorySummaryInternal(String city) {
        List<Residence> residences = residenceMapper.selectList(
                        new LambdaQueryWrapper<Residence>().eq(Residence::getActive, 1)).stream()
                .filter(item -> isBlank(city) || equalsIgnoreCase(item.getCity(), city))
                .toList();
        Set<Long> residenceIds = residences.stream().map(Residence::getId)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        List<RoomInventory> inventories = residenceIds.isEmpty() ? List.of()
                : inventoryMapper.selectList(new LambdaQueryWrapper<RoomInventory>()
                        .in(RoomInventory::getResidenceId, residenceIds));
        Map<String, Long> residencesByCity = residences.stream()
                .collect(Collectors.groupingBy(item -> Objects.toString(item.getCity(), "未设置"),
                        LinkedHashMap::new, Collectors.counting()));
        Map<String, Long> roomsByStatus = inventories.stream()
                .collect(Collectors.groupingBy(RoomInventory::getInventoryStatus,
                        LinkedHashMap::new, Collectors.counting()));
        LocalDate earliest = inventories.stream().map(RoomInventory::getEarliestStartDate)
                .filter(Objects::nonNull).min(Comparator.naturalOrder()).orElse(null);
        LocalDate latest = inventories.stream().map(RoomInventory::getLatestEndDate)
                .filter(Objects::nonNull).max(Comparator.naturalOrder()).orElse(null);
        LocalDateTime inventoryUpdatedAt = inventories.stream()
                .map(RoomInventory::getInventoryUpdatedAt)
                .filter(Objects::nonNull).max(Comparator.naturalOrder()).orElse(null);
        return new InventorySummary(city, residences.size(), inventories.size(),
                residencesByCity, roomsByStatus, earliest, latest, inventoryUpdatedAt);
    }

    private <T> T executeTool(String name, String arguments, Supplier<T> action,
                              Function<T, String> resultSummary) {
        long start = System.nanoTime();
        log.info("event=ai.tool.started name={} arguments={}", name, arguments);
        ToolCallEventContext.started(name);
        try {
            T result = action.get();
            log.info("event=ai.tool.completed name={} durationMs={} result={}",
                    name, elapsedMillis(start), resultSummary.apply(result));
            ToolCallEventContext.completed(name);
            return result;
        }
        catch (RuntimeException exception) {
            log.error(
                    "event=ai.tool.failed name={} durationMs={} errorType={} errorMessage={}",
                    name, elapsedMillis(start), exception.getClass().getSimpleName(),
                    sanitizeLogValue(exception.getMessage()), exception);
            ToolCallEventContext.failed(name);
            throw exception;
        }
    }

    private static String toolArguments(Object... keyValues) {
        if (keyValues.length % 2 != 0) {
            throw new IllegalArgumentException("Tool log arguments must use key/value pairs");
        }
        StringJoiner joiner = new StringJoiner(",", "{", "}");
        for (int index = 0; index < keyValues.length; index += 2) {
            joiner.add(sanitizeLogValue(keyValues[index]) + "="
                    + sanitizeLogValue(keyValues[index + 1]));
        }
        return joiner.toString();
    }

    private static String sanitizeLogValue(Object value) {
        String sanitized = Objects.toString(value, "null")
                .replace('\r', ' ')
                .replace('\n', ' ')
                .replace('\t', ' ');
        return sanitized.length() <= 200
                ? sanitized
                : sanitized.substring(0, 197) + "...";
    }

    private static long elapsedMillis(long start) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
    }

    private Map<Long, List<RoomPriceTier>> loadTiers(List<RoomInventory> inventories) {
        if (inventories.isEmpty()) {
            return Map.of();
        }
        return priceTierMapper.selectList(new LambdaQueryWrapper<RoomPriceTier>()
                        .in(RoomPriceTier::getInventoryId,
                                inventories.stream().map(RoomInventory::getId).toList())
                        .orderByAsc(RoomPriceTier::getMinWeeks)).stream()
                .collect(Collectors.groupingBy(RoomPriceTier::getInventoryId,
                        LinkedHashMap::new, Collectors.toList()));
    }

    private Map<Long, List<ResidenceNearbyPlace>> loadMatchingNearby(
            List<Residence> residences, String nearbyPlaceKeyword,
            Integer maxTravelMinutes) {
        if (residences.isEmpty() || isBlank(nearbyPlaceKeyword)) {
            return Map.of();
        }
        Set<Long> residenceIds = residences.stream().map(Residence::getId)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        return nearbyPlaceMapper.selectList(
                        new LambdaQueryWrapper<ResidenceNearbyPlace>()
                                .in(ResidenceNearbyPlace::getResidenceId, residenceIds)
                                .orderByAsc(ResidenceNearbyPlace::getSortOrder)).stream()
                .filter(place -> matchesNearbyName(place.getPlaceName(), nearbyPlaceKeyword))
                .filter(place -> withinTravelLimit(place, maxTravelMinutes))
                .sorted(nearbyTravelComparator())
                .collect(Collectors.groupingBy(
                        ResidenceNearbyPlace::getResidenceId,
                        LinkedHashMap::new,
                        Collectors.toList()));
    }

    private static boolean matchesNearbyName(String placeName, String keyword) {
        String name = canonicalPlaceName(placeName);
        String query = canonicalPlaceName(keyword);
        if (name.isBlank() || query.isBlank()) {
            return false;
        }
        if (name.contains(query) || query.contains(name)) {
            return true;
        }
        Map<String, Set<String>> aliases = Map.of(
                "ucl", Set.of("ucl", "universitycollegelondon"),
                "lse", Set.of("lse", "londonschoolofeconomics"),
                "kcl", Set.of("kcl", "kingscollegelondon"),
                "qmul", Set.of("qmul", "queenmaryuniversityoflondon"));
        return aliases.values().stream().anyMatch(group ->
                group.stream().anyMatch(query::contains)
                        && group.stream().anyMatch(name::contains));
    }

    private static boolean withinTravelLimit(
            ResidenceNearbyPlace place, Integer maxTravelMinutes) {
        if (maxTravelMinutes == null) {
            return true;
        }
        if (place.getMaxMinutes() != null) {
            return place.getMaxMinutes() <= maxTravelMinutes;
        }
        String description = normalize(place.getTravelDescription());
        return description.contains("walking distance")
                || description.contains("next door")
                || description.contains("doorstep");
    }

    private static String canonicalPlaceName(String value) {
        return Normalizer.normalize(
                        Objects.toString(value, ""), Normalizer.Form.NFKD)
                .replaceAll("\\p{M}+", "")
                .toLowerCase(Locale.ROOT)
                .replace('’', '\'')
                .replaceAll("[^a-z0-9]+", "");
    }

    private static MatchedRoom matchRoom(RoomInventory inventory,
                                         List<RoomPriceTier> tiers,
                                         LocalDate startFrom,
                                         LocalDate startTo,
                                         Integer stayWeeks,
                                         BigDecimal maxWeeklyPrice) {
        LocalDate matchedStart = null;
        LocalDate matchedEnd = null;
        if (startFrom != null) {
            matchedStart = startFrom.isAfter(inventory.getEarliestStartDate())
                    ? startFrom : inventory.getEarliestStartDate();
            if (matchedStart.isAfter(startTo)) {
                return null;
            }
            if (stayWeeks != null) {
                matchedEnd = matchedStart.plusWeeks(stayWeeks);
                if (matchedEnd.isAfter(inventory.getLatestEndDate())) {
                    return null;
                }
            }
            else if (matchedStart.isAfter(inventory.getLatestEndDate())) {
                return null;
            }
        }
        List<RoomPriceTier> matchedTiers;
        if (stayWeeks == null) {
            matchedTiers = tiers;
        }
        else {
            RoomPriceTier tier = findTier(tiers, stayWeeks);
            if (tier == null) {
                return null;
            }
            matchedTiers = List.of(tier);
        }
        BigDecimal effectivePrice = matchedTiers.stream()
                .map(RoomPriceTier::getWeeklyPrice)
                .filter(Objects::nonNull)
                .min(Comparator.naturalOrder())
                .orElse(null);
        if (maxWeeklyPrice != null
                && (effectivePrice == null || effectivePrice.compareTo(maxWeeklyPrice) > 0)) {
            return null;
        }
        return new MatchedRoom(inventory, matchedTiers, matchedStart, matchedEnd,
                effectivePrice);
    }

    private static ResidenceOfferGroup toGroup(
            Residence residence, List<MatchedRoom> rooms,
            List<ResidenceNearbyPlace> nearbyPlaces) {
        List<RoomMatch> roomMatches = rooms.stream()
                .limit(MAX_ROOMS_PER_RESIDENCE)
                .map(PropertyQueryTools::toRoomMatch)
                .toList();
        return new ResidenceOfferGroup(
                residence.getId(), residence.getSourceId(), residence.getName(),
                residence.getCity(), residence.getAddress(), residence.getStation(),
                residence.getZone(), residence.getLatitude(), residence.getLongitude(),
                residence.getMapUrl(),
                nearbyPlaces.stream().map(PropertyQueryTools::toNearbyMatch).toList(),
                roomMatches);
    }

    private static RoomMatch toRoomMatch(MatchedRoom room) {
        RoomInventory inventory = room.inventory();
        List<PriceTierItem> tiers = room.tiers().stream()
                .map(PropertyQueryTools::toTier).toList();
        BigDecimal total = room.matchedEnd() == null || room.effectiveWeeklyPrice() == null
                ? null
                : room.effectiveWeeklyPrice().multiply(BigDecimal.valueOf(
                        ChronoUnit.WEEKS.between(room.matchedStart(), room.matchedEnd())))
                .setScale(2, RoundingMode.HALF_UP);
        return new RoomMatch(inventory.getId(), inventory.getRoomCode(),
                inventory.getRoomName(), inventory.getRootType(),
                inventory.getEarliestStartDate(), inventory.getLatestEndDate(),
                room.matchedStart(), room.matchedEnd(),
                inventory.getInventoryStatus(), inventory.getRemainingQuantity(),
                tiers, room.effectiveWeeklyPrice(), total,
                inventory.getInventoryUpdatedAt(), inventory.getNote());
    }

    private static PriceTierItem toTier(RoomPriceTier tier) {
        return new PriceTierItem(tier.getMinWeeks(), tier.getMaxWeeks(),
                tier.getWeeklyPrice(), tier.getCurrency(), tier.getPriceUpdatedAt());
    }

    private static Comparator<MatchedRoom> roomComparator() {
        return Comparator.comparingInt((MatchedRoom room) ->
                        statusRank(room.inventory().getInventoryStatus()))
                .thenComparing(MatchedRoom::effectiveWeeklyPrice,
                        Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(room -> room.inventory().getRoomName());
    }

    private static Comparator<ResidenceOfferGroup> groupComparator(
            List<String> requestedResidenceNames) {
        Map<String, Integer> requestedOrder = new HashMap<>();
        if (requestedResidenceNames != null) {
            for (int i = 0; i < requestedResidenceNames.size(); i++) {
                requestedOrder.putIfAbsent(
                        canonicalResidenceName(requestedResidenceNames.get(i)), i);
            }
        }
        return Comparator.comparingInt((ResidenceOfferGroup group) ->
                        group.rooms().stream().map(RoomMatch::inventoryStatus)
                                .mapToInt(PropertyQueryTools::statusRank).min().orElse(99))
                .thenComparingInt(PropertyQueryTools::nearbyModeRank)
                .thenComparingInt(PropertyQueryTools::nearbyRank)
                .thenComparingInt(group -> requestedOrder.getOrDefault(
                        canonicalResidenceName(group.residenceName()), Integer.MAX_VALUE))
                .thenComparing(group -> group.rooms().stream()
                        .map(RoomMatch::weeklyPrice).filter(Objects::nonNull)
                        .min(Comparator.naturalOrder()).orElse(null),
                        Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(ResidenceOfferGroup::residenceName);
    }

    private static int nearbyRank(ResidenceOfferGroup group) {
        return group.nearbyMatches().stream()
                .map(NearbyPlaceItem::maxMinutes)
                .filter(Objects::nonNull)
                .min(Comparator.naturalOrder())
                .orElse(Integer.MAX_VALUE);
    }

    private static int nearbyModeRank(ResidenceOfferGroup group) {
        return group.nearbyMatches().stream()
                .map(NearbyPlaceItem::travelMode)
                .mapToInt(PropertyQueryTools::travelModeRank)
                .min()
                .orElse(Integer.MAX_VALUE);
    }

    private static Comparator<ResidenceNearbyPlace> nearbyTravelComparator() {
        return Comparator.comparingInt((ResidenceNearbyPlace place) ->
                        travelModeRank(place.getTravelMode()))
                .thenComparing(ResidenceNearbyPlace::getMaxMinutes,
                        Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(ResidenceNearbyPlace::getPlaceName,
                        Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER))
                .thenComparing(ResidenceNearbyPlace::getSortOrder,
                        Comparator.nullsLast(Comparator.naturalOrder()));
    }

    private static int travelModeRank(String travelMode) {
        return switch (Objects.toString(travelMode, "").toUpperCase(Locale.ROOT)) {
            case "WALK" -> 0;
            case "BIKE" -> 1;
            case "TUBE" -> 2;
            case "BUS" -> 3;
            default -> 4;
        };
    }

    private static int statusRank(String status) {
        return switch (Objects.toString(status, "UNKNOWN")) {
            case "AVAILABLE" -> 0;
            case "LIMITED" -> 1;
            case "UNKNOWN" -> 2;
            case "SOLD_OUT" -> 3;
            default -> 4;
        };
    }

    private static RoomPriceTier findTier(List<RoomPriceTier> tiers, int weeks) {
        return tiers.stream()
                .filter(tier -> tier.getMinWeeks() != null && weeks >= tier.getMinWeeks())
                .filter(tier -> tier.getMaxWeeks() == null || weeks <= tier.getMaxWeeks())
                .min(Comparator.comparing(RoomPriceTier::getMinWeeks).reversed())
                .orElse(null);
    }

    private static LocalDate parseDate(String value, String field) {
        if (isBlank(value)) {
            return null;
        }
        try {
            return LocalDate.parse(value.trim());
        }
        catch (RuntimeException ex) {
            throw new IllegalArgumentException(field + " 必须使用 YYYY-MM-DD 格式");
        }
    }

    private static void validateWeeks(Integer weeks) {
        if (weeks != null && (weeks < 1 || weeks > 104)) {
            throw new IllegalArgumentException("stayWeeks 必须在 1 到 104 之间");
        }
    }

    private static Set<String> splitRootTypes(String value) {
        if (isBlank(value)) {
            return Set.of();
        }
        return List.of(value.split("[,，]")).stream()
                .map(PropertyQueryTools::normalize)
                .filter(item -> !item.isBlank())
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    private static List<String> splitResidenceNames(String value) {
        if (isBlank(value)) {
            return List.of();
        }
        return List.of(value.split("[,，;；\\n]")).stream()
                .map(String::trim)
                .filter(item -> !item.isBlank())
                .distinct()
                .toList();
    }

    private static boolean matchesResidenceCandidates(
            Residence residence, List<String> candidates) {
        if (candidates == null || candidates.isEmpty()) {
            return true;
        }
        String nameKey = canonicalResidenceName(residence.getName());
        String sourceKey = canonicalResidenceName(residence.getSourceId());
        return candidates.stream()
                .map(PropertyQueryTools::canonicalResidenceName)
                .filter(candidate -> !candidate.isBlank())
                .anyMatch(candidate -> candidate.equals(nameKey)
                        || candidate.equals(sourceKey));
    }

    static String canonicalResidenceName(String value) {
        String normalized = Normalizer.normalize(
                        Objects.toString(value, ""), Normalizer.Form.NFKD)
                .replaceAll("\\p{M}+", "")
                .toLowerCase(Locale.ROOT)
                .replace('’', '\'')
                .replace('（', '(')
                .trim();
        normalized = normalized
                .replaceFirst("\\s*\\(.*$", "")
                .replaceAll("[^a-z0-9]+", " ")
                .trim()
                .replaceFirst("^(?:chapter|prestige|fresh|downing|mezzino|fusion|unite\\s+students?)\\s+", "")
                .replaceFirst("\\s+(?:residence|student\\s+accommodation)$", "");
        return normalized.replace(" ", "");
    }

    private static boolean matchesResidenceKeyword(Residence residence, String keyword) {
        if (isBlank(keyword)) {
            return true;
        }
        String query = normalize(keyword);
        return normalize(residence.getName()).contains(query)
                || normalize(residence.getSourceId()).contains(query)
                || normalize(residence.getAddress()).contains(query)
                || normalize(residence.getStation()).contains(query);
    }

    private static boolean equalsIgnoreCase(String first, String second) {
        return first != null && second != null && first.trim().equalsIgnoreCase(second.trim());
    }

    private static String normalize(String value) {
        return Objects.toString(value, "").trim().toLowerCase(Locale.ROOT);
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static String residenceName(Residence residence) {
        return residence == null ? "未知公寓" : residence.getName();
    }

    private static ResidenceItem toResidenceItem(Residence residence) {
        return new ResidenceItem(residence.getId(), residence.getSourceId(),
                residence.getName(), residence.getCity(), residence.getRegion(),
                residence.getZone(), residence.getAddress(), residence.getStation(),
                residence.getLatitude(), residence.getLongitude(), residence.getMapUrl());
    }

    private static NearbyPlaceItem toNearbyMatch(ResidenceNearbyPlace place) {
        return new NearbyPlaceItem(
                place.getPlaceType(), place.getPlaceName(),
                place.getTravelDescription(), place.getMinMinutes(),
                place.getMaxMinutes(), place.getTravelMode(),
                place.getDistanceMiles());
    }

    private static ResidenceDetailItem toDetailItem(
            Residence residence, ResidenceDetail detail,
            List<ResidenceNearbyPlace> nearbyPlaces) {
        List<String> facilities = detail == null || isBlank(detail.getFacilities())
                ? List.of()
                : detail.getFacilities().lines()
                        .map(String::strip)
                        .filter(item -> !item.isBlank())
                        .toList();
        return new ResidenceDetailItem(
                residence.getId(), residence.getSourceId(), residence.getName(),
                residence.getCity(), residence.getRegion(), residence.getZone(),
                residence.getAddress(), residence.getStation(),
                residence.getLatitude(), residence.getLongitude(), residence.getMapUrl(),
                detail == null ? null : detail.getPostcode(),
                detail == null ? null : detail.getTransportLines(),
                detail == null ? null : detail.getOfficialUrl(),
                detail == null ? null : detail.getPageTags(),
                facilities,
                nearbyPlaces.stream()
                        .sorted(nearbyTravelComparator())
                        .map(PropertyQueryTools::toNearbyMatch).toList(),
                detail == null ? null : detail.getDetailUpdatedAt());
    }

    private record MatchedRoom(
            RoomInventory inventory,
            List<RoomPriceTier> tiers,
            LocalDate matchedStart,
            LocalDate matchedEnd,
            BigDecimal effectiveWeeklyPrice
    ) {
    }

    public record RoomOfferSearchResult(
            String requestedCity,
            String residenceKeyword,
            List<String> requestedResidenceNames,
            String nearbyPlaceKeyword,
            Integer maxTravelMinutes,
            LocalDate startDateFrom,
            LocalDate startDateTo,
            Integer stayWeeks,
            int matchedResidenceCount,
            int availableResidenceCount,
            int soldOutResidenceCount,
            List<ResidenceOfferGroup> residences,
            List<String> warnings
    ) {
    }

    public record ResidenceOfferGroup(
            Long residenceId,
            String residenceSourceId,
            String residenceName,
            String city,
            String address,
            String station,
            String zone,
            BigDecimal latitude,
            BigDecimal longitude,
            String mapUrl,
            List<NearbyPlaceItem> nearbyMatches,
            List<RoomMatch> rooms
    ) {
    }

    public record RoomMatch(
            Long roomOfferId,
            String roomCode,
            String roomName,
            String rootType,
            LocalDate earliestStartDate,
            LocalDate latestEndDate,
            LocalDate matchedStartDate,
            LocalDate matchedEndDate,
            String inventoryStatus,
            Integer remainingQuantity,
            List<PriceTierItem> priceTiers,
            BigDecimal weeklyPrice,
            BigDecimal estimatedTotalPrice,
            LocalDateTime inventoryUpdatedAt,
            String note
    ) {
    }

    public record PriceTierItem(
            Integer minWeeks,
            Integer maxWeeks,
            BigDecimal weeklyPrice,
            String currency,
            LocalDateTime priceUpdatedAt
    ) {
    }

    public record RoomOfferQuote(
            Long roomOfferId,
            String residenceName,
            String roomName,
            LocalDate startDate,
            LocalDate endDate,
            Integer stayWeeks,
            String dateStatus,
            boolean available,
            String inventoryStatus,
            Integer remainingQuantity,
            PriceTierItem priceTier,
            BigDecimal weeklyPrice,
            BigDecimal estimatedTotalPrice,
            LocalDateTime inventoryUpdatedAt,
            List<String> warnings
    ) {
    }

    public record ResidenceListResult(
            String requestedCity,
            String keyword,
            int count,
            List<ResidenceItem> residences
    ) {
    }

    public record ResidenceDetailResult(
            String keyword,
            int count,
            List<ResidenceDetailItem> residences
    ) {
    }

    public record ResidenceDetailItem(
            Long residenceId,
            String residenceSourceId,
            String residenceName,
            String city,
            String region,
            String zone,
            String address,
            String station,
            BigDecimal latitude,
            BigDecimal longitude,
            String mapUrl,
            String postcode,
            String transportLines,
            String officialUrl,
            String pageTags,
            List<String> facilities,
            List<NearbyPlaceItem> nearbyPlaces,
            LocalDateTime detailUpdatedAt
    ) {
    }

    public record NearbyPlaceItem(
            String placeType,
            String placeName,
            String travelDescription,
            Integer minMinutes,
            Integer maxMinutes,
            String travelMode,
            BigDecimal distanceMiles
    ) {
    }

    public record ResidenceItem(
            Long residenceId,
            String residenceSourceId,
            String residenceName,
            String city,
            String region,
            String zone,
            String address,
            String station,
            BigDecimal latitude,
            BigDecimal longitude,
            String mapUrl
    ) {
    }

    public record InventorySummary(
            String requestedCity,
            int residenceCount,
            int roomOfferCount,
            Map<String, Long> residencesByCity,
            Map<String, Long> roomOffersByStatus,
            LocalDate earliestStartDate,
            LocalDate latestEndDate,
            LocalDateTime inventoryUpdatedAt
    ) {
    }
}
