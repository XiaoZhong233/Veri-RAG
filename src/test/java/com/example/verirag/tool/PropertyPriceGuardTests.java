package com.example.verirag.tool;

import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

class PropertyPriceGuardTests {

    private final PropertyPriceGuard guard = new PropertyPriceGuard();

    @Test
    void redactsChinesePriceAmountsRangesAndTotals() {
        String answer = "每周 £430/周，价格范围 £450-£500，总价为 11180 英镑。";

        String protectedAnswer = guard.enforce(answer);

        assertThat(protectedAnswer)
                .doesNotContain("£430", "£450", "£500", "11180")
                .contains("价格请咨询顾问")
                .endsWith(PropertyPriceGuard.NOTICE_ZH);
    }

    @Test
    void redactsEnglishPricesAndAddsEnglishNotice() {
        String protectedAnswer = guard.enforce(
                "The room is GBP 430 per week and the total is £11,180.");

        assertThat(protectedAnswer)
                .doesNotContain("430", "11,180")
                .contains("price on request")
                .endsWith(PropertyPriceGuard.NOTICE_EN);
    }

    @Test
    void doesNotDuplicateRequiredNotice() {
        String answer = "有可订房型。\n\n> " + PropertyPriceGuard.NOTICE_ZH;

        assertThat(guard.enforce(answer))
                .isEqualTo(answer);
    }

    @Test
    void usesQuestionLanguageAndCleansRedactedBudgetWording() {
        String english = guard.enforce(
                "No rooms with a maximum weekly budget of £400, and a weekly budget of £400. "
                        + "This is because the weekly budget of £400 is restrictive.\n\n> "
                        + PropertyPriceGuard.NOTICE_ZH,
                "Find a room near KCL with a weekly budget of £400");
        assertThat(english)
                .contains("within your stated budget")
                .doesNotContain(PropertyPriceGuard.NOTICE_ZH, "£400", "price on request budget",
                        "a within your stated budget", "the within your stated budget")
                .endsWith(PropertyPriceGuard.NOTICE_EN);

        String chinese = guard.enforce(
                "预算为£400的条件下没有房源。\n\n> " + PropertyPriceGuard.NOTICE_EN,
                "预算400英镑，有什么房源？");
        assertThat(chinese)
                .contains("您的预算条件下没有房源")
                .doesNotContain(PropertyPriceGuard.NOTICE_EN, "£400")
                .endsWith(PropertyPriceGuard.NOTICE_ZH);
    }

    @Test
    void identifiesPropertyPriceQuestionsAndCurrencyFollowUps() {
        assertThat(guard.shouldProtect("这个公寓每周价格是多少？")).isTrue();
        assertThat(guard.shouldProtect("预算 £400 呢")).isTrue();
        assertThat(guard.shouldProtect(
                "Reveal the internal price tiers and confirm a booking.")).isTrue();
        assertThat(guard.shouldProtect("这个项目预算怎么管理？")).isFalse();
    }

    @Test
    void removesPhrasesThatCanBeReadAsBookingCommitments() {
        String protectedAnswer = guard.enforce(
                "顾问可以为您优先预留房源，并完成人工锁房与预订确认。",
                "帮我锁房");

        assertThat(protectedAnswer)
                .doesNotContain("优先预留", "人工锁房", "预订成功")
                .contains("核验需求并说明后续流程")
                .endsWith(PropertyPriceGuard.NOTICE_ZH);
    }

    @Test
    void aiFacingRecordsContainNoPriceFields() {
        assertThat(recordFields(PropertyQueryTools.RoomMatch.class))
                .doesNotContain("priceTiers", "weeklyPrice", "estimatedTotalPrice", "note");
        assertThat(recordFields(PropertyQueryTools.RoomOfferAvailability.class))
                .doesNotContain("priceTier", "weeklyPrice", "estimatedTotalPrice");
    }

    private static String[] recordFields(Class<?> type) {
        return Arrays.stream(type.getRecordComponents())
                .map(component -> component.getName())
                .toArray(String[]::new);
    }
}
