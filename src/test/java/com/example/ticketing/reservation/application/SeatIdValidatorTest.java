package com.example.ticketing.reservation.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.ticketing.support.RedisIntegrationTestSupport;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class SeatIdValidatorTest extends RedisIntegrationTestSupport {

    @Autowired
    private SeatIdValidator validator;

    @Test
    void acceptsSeatIdInRange() {
        assertThat(validator.isValid("seat-1")).isTrue();
        assertThat(validator.isValid("seat-2000")).isTrue();
    }

    @Test
    void rejectsZeroSeat() {
        assertThat(validator.isValid("seat-0")).isFalse();
    }

    @Test
    void rejectsOutOfRangeSeat() {
        assertThat(validator.isValid("seat-2001")).isFalse();
    }

    @Test
    void rejectsInvalidFormat() {
        assertThat(validator.isValid("A-10")).isFalse();
        assertThat(validator.isValid("seat-x")).isFalse();
        assertThat(validator.isValid("")).isFalse();
    }
}

