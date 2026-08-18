package com.example.verirag.tool;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PropertyIntentClassifierTests {

    @Test
    void parsesExactlyOneIntentToken() {
        assertThat(PropertyIntentClassifier.parseIntent("RECOMMEND"))
                .isEqualTo(PropertyQueryIntent.RECOMMEND);
        assertThat(PropertyIntentClassifier.parseIntent("```text\nDETAIL\n```"))
                .isEqualTo(PropertyQueryIntent.DETAIL);
        assertThat(PropertyIntentClassifier.parseIntent("GUIDANCE"))
                .isEqualTo(PropertyQueryIntent.GUIDANCE);
        assertThat(PropertyIntentClassifier.parseIntent("CLARIFY"))
                .isEqualTo(PropertyQueryIntent.CLARIFY);
        assertThat(PropertyIntentClassifier.parseIntent("RESTRICTED"))
                .isEqualTo(PropertyQueryIntent.RESTRICTED);
        assertThat(PropertyIntentClassifier.parseIntent("ACKNOWLEDGE"))
                .isEqualTo(PropertyQueryIntent.ACKNOWLEDGE);
    }

    @Test
    void fallsBackWhenModelOutputIsMissingOrAmbiguous() {
        assertThat(PropertyIntentClassifier.parseIntent("无法判断"))
                .isEqualTo(PropertyQueryIntent.NONE);
        assertThat(PropertyIntentClassifier.parseIntent("DETAIL 或 RECOMMEND"))
                .isEqualTo(PropertyQueryIntent.NONE);
    }
}
