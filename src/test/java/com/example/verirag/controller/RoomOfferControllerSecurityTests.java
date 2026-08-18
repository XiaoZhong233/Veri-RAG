package com.example.verirag.controller;

import org.junit.jupiter.api.Test;
import org.springframework.security.access.prepost.PreAuthorize;

import static org.assertj.core.api.Assertions.assertThat;

class RoomOfferControllerSecurityTests {

    @Test
    void protectsAllInventoryAndPriceEndpointsWithAdminRole() {
        PreAuthorize authorization =
                RoomOfferController.class.getAnnotation(PreAuthorize.class);

        assertThat(authorization).isNotNull();
        assertThat(authorization.value()).isEqualTo("hasRole('ADMIN')");
    }
}
