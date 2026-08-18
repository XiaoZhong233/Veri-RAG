package com.example.verirag.tool;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.example.verirag.dto.SalesRecommendationView;
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
import com.example.verirag.service.SalesRecommendationService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PropertyQueryToolsTests {

    @Mock
    private ResidenceMapper residenceMapper;
    @Mock
    private ResidenceDetailMapper residenceDetailMapper;
    @Mock
    private ResidenceNearbyPlaceMapper nearbyPlaceMapper;
    @Mock
    private RoomInventoryMapper inventoryMapper;
    @Mock
    private RoomPriceTierMapper priceTierMapper;
    @Mock
    private SalesRecommendationService salesRecommendationService;

    private PropertyQueryTools tools;

    @BeforeAll
    static void initializeMybatisMetadata() {
        MapperBuilderAssistant assistant =
                new MapperBuilderAssistant(new MybatisConfiguration(), "");
        assistant.setCurrentNamespace(PropertyQueryToolsTests.class.getName());
        TableInfoHelper.initTableInfo(assistant, Residence.class);
        TableInfoHelper.initTableInfo(assistant, ResidenceDetail.class);
        TableInfoHelper.initTableInfo(assistant, ResidenceNearbyPlace.class);
        TableInfoHelper.initTableInfo(assistant, RoomInventory.class);
        TableInfoHelper.initTableInfo(assistant, RoomPriceTier.class);
    }

    @BeforeEach
    void setUp() {
        tools = new PropertyQueryTools(
                residenceMapper, residenceDetailMapper, nearbyPlaceMapper,
                inventoryMapper, priceTierMapper, salesRecommendationService);
    }

    @Test
    void searchesAndGroupsByResidenceWithoutExposingPrices() throws Exception {
        Residence first = residence(1L, "chapter-islington", "Chapter Islington");
        Residence second = residence(2L, "chapter-highbury", "Chapter Highbury");
        RoomInventory available = inventory(11L, 1L, "AVAILABLE", "Classic Ensuite");
        RoomInventory soldOut = inventory(22L, 2L, "SOLD_OUT", "Silver Studio");
        RoomPriceTier firstTier = tier(101L, 11L, 20, 39, "430");
        RoomPriceTier secondTier = tier(202L, 22L, 20, 39, "445");

        when(residenceMapper.selectList(any(Wrapper.class))).thenReturn(List.of(first, second));
        when(inventoryMapper.selectList(any(Wrapper.class)))
                .thenReturn(List.of(available, soldOut));
        when(priceTierMapper.selectList(any(Wrapper.class)))
                .thenReturn(List.of(firstTier, secondTier));

        PropertyQueryTools.RoomOfferSearchResult result = tools.searchRoomOffers(
                "London", null, "Chapter Islington, Chapter Highbury",
                null, null, null,
                "2026-09-01", "2026-09-30",
                26, null, null, true, 8);

        assertThat(result.matchedResidenceCount()).isEqualTo(2);
        assertThat(result.availableResidenceCount()).isEqualTo(1);
        assertThat(result.soldOutResidenceCount()).isEqualTo(1);
        assertThat(result.residences()).extracting(
                PropertyQueryTools.ResidenceOfferGroup::residenceName)
                .containsExactly("Chapter Islington", "Chapter Highbury");
        assertThat(result.priceDisclosure())
                .isEqualTo("CONSULTANT_CONFIRMATION_REQUIRED");
        String json = new ObjectMapper().findAndRegisterModules()
                .writeValueAsString(result);
        assertThat(json)
                .doesNotContain("weeklyPrice", "priceTiers", "estimatedTotalPrice", "430");
    }

    @Test
    void restrictsResultsToKnowledgeBaseResidenceCandidates() {
        Residence islington = residence(1L, "chapter-islington", "Chapter Islington");
        Residence highbury = residence(2L, "chapter-highbury", "Chapter Highbury");
        RoomInventory islingtonRoom = inventory(
                11L, 1L, "AVAILABLE", "Classic Ensuite");
        RoomInventory highburyRoom = inventory(
                22L, 2L, "AVAILABLE", "Silver Studio");

        when(residenceMapper.selectList(any(Wrapper.class)))
                .thenReturn(List.of(islington, highbury));
        when(inventoryMapper.selectList(any(Wrapper.class)))
                .thenReturn(List.of(islingtonRoom, highburyRoom));
        when(priceTierMapper.selectList(any(Wrapper.class)))
                .thenReturn(List.of(tier(202L, 22L, 20, 39, "445")));

        PropertyQueryTools.RoomOfferSearchResult result = tools.searchRoomOffers(
                "London", null, "Highbury Residence",
                null, null, null,
                "2026-09-01", "2026-09-30",
                26, null, null, false, 4);

        assertThat(result.requestedResidenceNames())
                .containsExactly("Highbury Residence");
        assertThat(result.residences())
                .extracting(PropertyQueryTools.ResidenceOfferGroup::residenceName)
                .containsExactly("Chapter Highbury");
    }

    @Test
    void filtersOffersByStructuredNearbyUniversityAndTravelTime() {
        Residence islington = residence(1L, "islington-residence", "Islington Residence");
        Residence farAway = residence(2L, "far-away", "Far Away Residence");
        ResidenceNearbyPlace nearbyUcl = nearby(1L, 1L,
                "University College London (UCL)", 18);
        ResidenceNearbyPlace farUcl = nearby(2L, 2L,
                "UCL (University College London)", 30);
        RoomInventory room = inventory(11L, 1L, "AVAILABLE", "Classic Ensuite");

        when(residenceMapper.selectList(any(Wrapper.class)))
                .thenReturn(List.of(islington, farAway));
        when(nearbyPlaceMapper.selectList(any(Wrapper.class)))
                .thenReturn(List.of(nearbyUcl, farUcl));
        when(inventoryMapper.selectList(any(Wrapper.class))).thenReturn(List.of(room));
        when(priceTierMapper.selectList(any(Wrapper.class)))
                .thenReturn(List.of(tier(101L, 11L, 20, 39, "430")));

        PropertyQueryTools.RoomOfferSearchResult result = tools.searchRoomOffers(
                "London", null, null, "UCL", 25, null,
                "2026-09-01", "2026-09-30",
                26, null, null, false, 4);

        assertThat(result.residences())
                .extracting(PropertyQueryTools.ResidenceOfferGroup::residenceName)
                .containsExactly("Islington Residence");
        assertThat(result.residences().getFirst().nearbyMatches())
                .singleElement()
                .satisfies(match -> {
                    assertThat(match.placeName()).contains("UCL");
                    assertThat(match.maxMinutes()).isEqualTo(18);
                });
    }

    @Test
    void defaultsNearbyQueriesToTwentyFiveMinutesWhenModelOmitsLimit() {
        Residence nearbyResidence = residence(1L, "nearby", "Nearby Residence");
        Residence farResidence = residence(2L, "far", "Far Residence");
        ResidenceNearbyPlace nearbyUcl = nearby(1L, 1L, "UCL", 25);
        ResidenceNearbyPlace farUcl = nearby(2L, 2L, "UCL", 30);
        RoomInventory nearbyRoom = inventory(
                11L, 1L, "AVAILABLE", "Classic Ensuite");
        RoomInventory farRoom = inventory(
                22L, 2L, "AVAILABLE", "Classic Ensuite");

        when(residenceMapper.selectList(any(Wrapper.class)))
                .thenReturn(List.of(nearbyResidence, farResidence));
        when(nearbyPlaceMapper.selectList(any(Wrapper.class)))
                .thenReturn(List.of(nearbyUcl, farUcl));
        when(inventoryMapper.selectList(any(Wrapper.class)))
                .thenReturn(List.of(nearbyRoom, farRoom));
        when(priceTierMapper.selectList(any(Wrapper.class)))
                .thenReturn(List.of(
                        tier(101L, 11L, 20, 39, "430"),
                        tier(202L, 22L, 20, 39, "430")));

        PropertyQueryTools.RoomOfferSearchResult result = tools.searchRoomOffers(
                "London", null, null, "UCL", null, null,
                "2026-09-01", "2026-09-30", 26,
                null, null, false, 4);

        assertThat(result.maxTravelMinutes()).isEqualTo(25);
        assertThat(result.residences())
                .extracting(PropertyQueryTools.ResidenceOfferGroup::residenceName)
                .containsExactly("Nearby Residence");
    }

    @Test
    void keepsResidenceWhenAnyTravelOptionMatchesAndReturnsPreferredEligibleMode() {
        Residence paddington = residence(
                5L, "paddington", "Paddington Citi View");
        ResidenceNearbyPlace bus = nearby(
                10L, 5L, "Imperial College London", 28);
        bus.setTravelMode("BUS");
        bus.setTravelDescription("28 mins by bus");
        ResidenceNearbyPlace bike = nearby(
                11L, 5L, "Imperial College London", 10);
        bike.setTravelMode("BIKE");
        bike.setTravelDescription("10 mins by bike");
        RoomInventory room = inventory(
                68L, 5L, "AVAILABLE", "Bronze Studio");

        when(residenceMapper.selectList(any(Wrapper.class)))
                .thenReturn(List.of(paddington));
        when(nearbyPlaceMapper.selectList(any(Wrapper.class)))
                .thenReturn(List.of(bus, bike));
        when(inventoryMapper.selectList(any(Wrapper.class)))
                .thenReturn(List.of(room));
        when(priceTierMapper.selectList(any(Wrapper.class)))
                .thenReturn(List.of(tier(202L, 68L, 20, 39, "560")));

        PropertyQueryTools.RoomOfferSearchResult result = tools.searchRoomOffers(
                "London", null, null, "Imperial College London", 25, null,
                "2026-09-01", "2026-09-30",
                26, null, null, false, 4);

        assertThat(result.residences()).singleElement().satisfies(group ->
                assertThat(group.nearbyMatches()).singleElement().satisfies(match -> {
                    assertThat(match.travelMode()).isEqualTo("BIKE");
                    assertThat(match.maxMinutes()).isEqualTo(10);
                }));
    }

    @Test
    void ranksByMinutesByDefaultAndOnlyPrefersModeWhenUserRequestsIt() {
        Residence fastTube = residence(1L, "fast-tube", "Fast Tube");
        Residence slowerWalk = residence(2L, "slower-walk", "Slower Walk");
        ResidenceNearbyPlace tube = nearby(
                1L, 1L, "University College London (UCL)", 10);
        tube.setTravelMode("TUBE");
        ResidenceNearbyPlace walk = nearby(
                2L, 2L, "University College London (UCL)", 20);
        walk.setTravelMode("WALK");
        RoomInventory tubeRoom = inventory(
                11L, 1L, "AVAILABLE", "Classic Ensuite");
        RoomInventory walkRoom = inventory(
                22L, 2L, "AVAILABLE", "Classic Ensuite");

        when(residenceMapper.selectList(any(Wrapper.class)))
                .thenReturn(List.of(fastTube, slowerWalk));
        when(nearbyPlaceMapper.selectList(any(Wrapper.class)))
                .thenReturn(List.of(tube, walk));
        when(inventoryMapper.selectList(any(Wrapper.class)))
                .thenReturn(List.of(tubeRoom, walkRoom));
        when(priceTierMapper.selectList(any(Wrapper.class)))
                .thenReturn(List.of(
                        tier(101L, 11L, 20, 39, "430"),
                        tier(202L, 22L, 20, 39, "430")));

        PropertyQueryTools.RoomOfferSearchResult defaultResult = tools.searchRoomOffers(
                "London", null, null, "UCL", 25, null,
                null, null, null, null, null, false, 4);
        PropertyQueryTools.RoomOfferSearchResult walkPreferredResult = tools.searchRoomOffers(
                "London", null, null, "UCL", 25, "WALK",
                null, null, null, null, null, false, 4);

        assertThat(defaultResult.residences())
                .extracting(PropertyQueryTools.ResidenceOfferGroup::residenceName)
                .containsExactly("Fast Tube", "Slower Walk");
        assertThat(walkPreferredResult.residences())
                .extracting(PropertyQueryTools.ResidenceOfferGroup::residenceName)
                .containsExactly("Slower Walk", "Fast Tube");
    }

    @Test
    void promotesSalesResidenceOnlyAcrossCandidatesWithSimilarTravelTime() {
        Residence finsbury = residence(1L, "finsbury", "Finsbury House");
        Residence islington = residence(2L, "islington", "Islington Residence");
        Residence highbury = residence(3L, "highbury", "Highbury Residence");
        Residence drapery = residence(4L, "drapery", "Drapery Place Residence");
        Residence lyra = residence(5L, "lyra", "The Lyra Residence");
        List<Residence> residences =
                List.of(finsbury, islington, highbury, drapery, lyra);
        List<ResidenceNearbyPlace> nearbyPlaces = List.of(
                nearby(1L, 1L, "UCL", 15),
                nearby(2L, 2L, "UCL", 18),
                nearby(3L, 3L, "UCL", 18),
                nearby(4L, 4L, "UCL", 20),
                nearby(5L, 5L, "UCL", 23));
        nearbyPlaces.get(4).setTravelMode("WALK");
        List<RoomInventory> rooms = List.of(
                inventory(11L, 1L, "AVAILABLE", "Classic Ensuite"),
                inventory(22L, 2L, "AVAILABLE", "Classic Ensuite"),
                inventory(33L, 3L, "AVAILABLE", "Classic Ensuite"),
                inventory(44L, 4L, "AVAILABLE", "Premium Ensuite"),
                inventory(55L, 5L, "AVAILABLE", "Bronze Studio"));

        when(residenceMapper.selectList(any(Wrapper.class))).thenReturn(residences);
        when(nearbyPlaceMapper.selectList(any(Wrapper.class)))
                .thenReturn(nearbyPlaces);
        when(inventoryMapper.selectList(any(Wrapper.class))).thenReturn(rooms);
        when(priceTierMapper.selectList(any(Wrapper.class)))
                .thenReturn(List.of(
                        tier(101L, 11L, 20, 39, "399"),
                        tier(202L, 22L, 20, 39, "410"),
                        tier(303L, 33L, 20, 39, "415"),
                        tier(404L, 44L, 20, 39, "419"),
                        tier(505L, 55L, 20, 39, "399")));
        when(salesRecommendationService.enabledRecommendations()).thenReturn(List.of(
                new SalesRecommendationView(
                        1L, 4L, "drapery", "Drapery Place Residence",
                        "London", 100, 1, null, null, null)));

        PropertyQueryTools.RoomOfferSearchResult result = tools.searchRoomOffers(
                "London", null, null, "UCL", 25, null,
                null, null, null, null, null, false, 4);

        assertThat(result.residences())
                .extracting(PropertyQueryTools.ResidenceOfferGroup::residenceName)
                .startsWith("Finsbury House", "Drapery Place Residence")
                .doesNotContain("The Lyra Residence");
    }

    @Test
    void capsRoomOptionsAcrossFourResidencesAtSix() {
        List<Residence> residences = List.of(
                residence(1L, "one", "Residence One"),
                residence(2L, "two", "Residence Two"),
                residence(3L, "three", "Residence Three"),
                residence(4L, "four", "Residence Four"));
        List<RoomInventory> rooms = new java.util.ArrayList<>();
        List<RoomPriceTier> tiers = new java.util.ArrayList<>();
        long roomId = 10;
        long tierId = 100;
        for (Residence residence : residences) {
            for (int option = 1; option <= 2; option++) {
                RoomInventory room = inventory(
                        roomId, residence.getId(), "AVAILABLE",
                        "Classic Ensuite " + option);
                rooms.add(room);
                tiers.add(tier(tierId++, roomId++, 20, 39, "430"));
            }
        }
        when(residenceMapper.selectList(any(Wrapper.class))).thenReturn(residences);
        when(inventoryMapper.selectList(any(Wrapper.class))).thenReturn(rooms);
        when(priceTierMapper.selectList(any(Wrapper.class))).thenReturn(tiers);

        PropertyQueryTools.RoomOfferSearchResult result = tools.searchRoomOffers(
                "London", null, null, null, null, null,
                "2026-09-01", "2026-09-30", 26,
                null, null, false, 4);

        assertThat(result.residences()).hasSize(4);
        assertThat(result.residences()).extracting(group -> group.rooms().size())
                .containsExactly(2, 2, 1, 1);
        assertThat(result.residences().stream()
                .mapToInt(group -> group.rooms().size()).sum()).isEqualTo(6);
    }

    @Test
    void checksSpecificOfferWithoutExposingPrice() {
        Residence residence = residence(1L, "chapter-islington", "Chapter Islington");
        RoomInventory inventory = inventory(11L, 1L, "AVAILABLE", "Classic Ensuite");
        RoomPriceTier priceTier = tier(101L, 11L, 20, 39, "430");

        when(inventoryMapper.selectById(11L)).thenReturn(inventory);
        when(residenceMapper.selectById(1L)).thenReturn(residence);
        when(priceTierMapper.selectList(any(Wrapper.class))).thenReturn(List.of(priceTier));

        PropertyQueryTools.RoomOfferAvailability availability =
                tools.checkRoomOfferAvailability(
                11L, "2026-09-01", "2027-03-01", null);

        assertThat(availability.stayWeeks()).isEqualTo(26);
        assertThat(availability.dateStatus()).isEqualTo("MATCHED");
        assertThat(availability.available()).isTrue();
        assertThat(availability.priceDisclosure())
                .isEqualTo("CONSULTANT_CONFIRMATION_REQUIRED");
    }

    @Test
    void exposesFiveSpringAiToolSchemas() {
        ToolCallback[] callbacks = ToolCallbacks.from(tools);
        Set<String> names = Arrays.stream(callbacks)
                .map(callback -> callback.getToolDefinition().name())
                .collect(Collectors.toSet());

        assertThat(names).containsExactlyInAnyOrder(
                "search_room_offers",
                "check_room_offer_availability",
                "get_residence_details",
                "list_residences",
                "get_inventory_summary");
        ToolCallback search = Arrays.stream(callbacks)
                .filter(callback -> "search_room_offers"
                        .equals(callback.getToolDefinition().name()))
                .findFirst().orElseThrow();
        assertThat(search.getToolDefinition().inputSchema())
                .contains("startDateFrom", "stayWeeks", "residenceNames",
                        "nearbyPlaceKeyword", "maxTravelMinutes",
                        "preferredTravelModes")
                .doesNotContain("minResidences");
    }

    private static Residence residence(Long id, String sourceId, String name) {
        Residence residence = new Residence();
        residence.setId(id);
        residence.setSourceId(sourceId);
        residence.setName(name);
        residence.setCity("London");
        residence.setAddress("Test address");
        residence.setActive(1);
        return residence;
    }

    private static RoomInventory inventory(Long id, Long residenceId,
                                           String status, String roomName) {
        RoomInventory inventory = new RoomInventory();
        inventory.setId(id);
        inventory.setResidenceId(residenceId);
        inventory.setRoomCode("room-" + id);
        inventory.setRoomName(roomName);
        inventory.setRootType(roomName.contains("Studio") ? "Studio" : "Ensuite");
        inventory.setEarliestStartDate(LocalDate.of(2026, 8, 29));
        inventory.setLatestEndDate(LocalDate.of(2027, 8, 24));
        inventory.setInventoryStatus(status);
        inventory.setRemainingQuantity("SOLD_OUT".equals(status) ? 0 : 5);
        inventory.setInventoryUpdatedAt(LocalDateTime.of(2026, 7, 24, 12, 0));
        return inventory;
    }

    private static RoomPriceTier tier(Long id, Long inventoryId, int minWeeks,
                                      int maxWeeks, String weeklyPrice) {
        RoomPriceTier tier = new RoomPriceTier();
        tier.setId(id);
        tier.setInventoryId(inventoryId);
        tier.setMinWeeks(minWeeks);
        tier.setMaxWeeks(maxWeeks);
        tier.setWeeklyPrice(new BigDecimal(weeklyPrice));
        tier.setCurrency("GBP");
        tier.setPriceUpdatedAt(LocalDateTime.of(2026, 7, 24, 12, 0));
        return tier;
    }

    private static ResidenceNearbyPlace nearby(
            Long id, Long residenceId, String name, int minutes) {
        ResidenceNearbyPlace place = new ResidenceNearbyPlace();
        place.setId(id);
        place.setResidenceId(residenceId);
        place.setPlaceType("UNIVERSITY");
        place.setPlaceName(name);
        place.setTravelDescription(minutes + " mins via tube");
        place.setMinMinutes(minutes);
        place.setMaxMinutes(minutes);
        place.setTravelMode("TUBE");
        place.setSortOrder(0);
        return place;
    }
}
