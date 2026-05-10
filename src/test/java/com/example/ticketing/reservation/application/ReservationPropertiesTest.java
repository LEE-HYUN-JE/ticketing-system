package com.example.ticketing.reservation.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.ticketing.support.RedisIntegrationTestSupport;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class ReservationPropertiesTest extends RedisIntegrationTestSupport {

    @Autowired
    private ReservationProperties properties;

    @Test
    void bindsIdempotencyProperties() {
        assertThat(properties.idempotencyTtlSeconds()).isEqualTo(600);
        assertThat(properties.idempotencyKeyMaxLength()).isEqualTo(120);
    }
}
