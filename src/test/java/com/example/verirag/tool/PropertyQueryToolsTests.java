package com.example.verirag.tool;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.example.verirag.entity.Residence;
import com.example.verirag.entity.RoomInventory;
import com.example.verirag.entity.RoomPriceTier;
import com.example.verirag.mapper.ResidenceMapper;
import com.example.verirag.mapper.RoomInventoryMapper;
import com.example.verirag.mapper.RoomPriceTierMapper;
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
    private RoomInventoryMapper inventoryMapper;
    @Mock
    private RoomPriceTierMapper priceTierMapper;

    private PropertyQueryTools tools;

    @BeforeAll
    static void initializeMybatisMetadata() {
        MapperBuilderAssistant assistant =
                new MapperBuilderAssistant(new MybatisConfiguration(), "");
        assistant.setCurrentNamespace(PropertyQueryToolsTests.class.getName());
        TableInfoHelper.initTableInfo(assistant, Residence.class);
        TableInfoHelper.initTableInfo(assistant, RoomInventory.class);
        TableInfoHelper.initTableInfo(assistant, RoomPriceTier.class);
    }

    @BeforeEach
    void setUp() {
        tools = new PropertyQueryTools(residenceMapper, inventoryMapper, priceTierMapper);
    }

    @Test
    void searchesAndGroupsByResidenceWithMatchedTier() {
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
                "2026-09-01", "2026-09-30",
                26, null, null, true, 8);

        assertThat(result.matchedResidenceCount()).isEqualTo(2);
        assertThat(result.availableResidenceCount()).isEqualTo(1);
        assertThat(result.soldOutResidenceCount()).isEqualTo(1);
        assertThat(result.residences()).extracting(
                PropertyQueryTools.ResidenceOfferGroup::residenceName)
                .containsExactly("Chapter Islington", "Chapter Highbury");
        assertThat(result.residences().getFirst().rooms().getFirst().weeklyPrice())
                .isEqualByComparingTo("430");
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
                "2026-09-01", "2026-09-30",
                26, null, null, false, 4);

        assertThat(result.requestedResidenceNames())
                .containsExactly("Highbury Residence");
        assertThat(result.residences())
                .extracting(PropertyQueryTools.ResidenceOfferGroup::residenceName)
                .containsExactly("Chapter Highbury");
    }

    @Test
    void quoteUsesDatesToCalculateWeeksAndTotal() {
        Residence residence = residence(1L, "chapter-islington", "Chapter Islington");
        RoomInventory inventory = inventory(11L, 1L, "AVAILABLE", "Classic Ensuite");
        RoomPriceTier priceTier = tier(101L, 11L, 20, 39, "430");

        when(inventoryMapper.selectById(11L)).thenReturn(inventory);
        when(residenceMapper.selectById(1L)).thenReturn(residence);
        when(priceTierMapper.selectList(any(Wrapper.class))).thenReturn(List.of(priceTier));

        PropertyQueryTools.RoomOfferQuote quote = tools.quoteRoomOffer(
                11L, "2026-09-01", "2027-03-01", null);

        assertThat(quote.stayWeeks()).isEqualTo(26);
        assertThat(quote.dateStatus()).isEqualTo("MATCHED");
        assertThat(quote.available()).isTrue();
        assertThat(quote.estimatedTotalPrice()).isEqualByComparingTo("11180.00");
    }

    @Test
    void exposesFourSpringAiToolSchemas() {
        ToolCallback[] callbacks = ToolCallbacks.from(tools);
        Set<String> names = Arrays.stream(callbacks)
                .map(callback -> callback.getToolDefinition().name())
                .collect(Collectors.toSet());

        assertThat(names).containsExactlyInAnyOrder(
                "search_room_offers",
                "quote_room_offer",
                "list_residences",
                "get_inventory_summary");
        ToolCallback search = Arrays.stream(callbacks)
                .filter(callback -> "search_room_offers"
                        .equals(callback.getToolDefinition().name()))
                .findFirst().orElseThrow();
        assertThat(search.getToolDefinition().inputSchema())
                .contains("startDateFrom", "stayWeeks", "residenceNames")
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
}
