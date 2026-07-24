package com.example.verirag.util;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ResidenceDetailMarkdownReaderTests {

    private final ResidenceDetailMarkdownReader reader =
            new ResidenceDetailMarkdownReader();

    @Test
    void parsesResidenceDetailsAndNearbyTravelMetrics() {
        var records = reader.parse("""
                # Londonist 伦敦学生公寓资料

                ## 公寓索引

                | 公寓 |
                |---|

                ## Islington Residence

                - 官网公寓 ID：12
                - 城市：London
                - 地址：32-34 Market Rd, London N7 9AW
                - 区域：Zone 2
                - 邮编：N7 9AW
                - 最近车站：Caledonian Tube
                - 交通线路：Piccadilly Line
                - 页面标签：High Demand
                - 设施：Wifi、Laundry、Gym
                - 来源：https://example.test/residences/islington-residence

                ### 附近学校/大学

                - University College London (UCL): 18 mins via tube
                - London Metropolitan University — 15 mins walk

                ### 附近地标与生活配套

                - Camden Market – 1.5 miles

                ## 数据说明

                本节不是公寓详情。
                """);

        assertThat(records).singleElement().satisfies(record -> {
            assertThat(record.sourceId()).isEqualTo("islington-residence");
            assertThat(record.facilities()).containsExactly("Wifi", "Laundry", "Gym");
            assertThat(record.nearbyPlaces()).hasSize(3);
            assertThat(record.nearbyPlaces().getFirst().maxMinutes()).isEqualTo(18);
            assertThat(record.nearbyPlaces().getFirst().travelMode()).isEqualTo("TUBE");
            assertThat(record.nearbyPlaces().get(2).distanceMiles())
                    .isEqualByComparingTo("1.5");
        });
    }
}
