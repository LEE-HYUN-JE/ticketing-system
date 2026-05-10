package com.example.ticketing.reservation.integration;

import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.ticketing.queue.infrastructure.QueueRedisKeys;
import com.example.ticketing.reservation.infrastructure.ReservationRedisKeys;
import com.example.ticketing.support.RedisIntegrationTestSupport;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@AutoConfigureMockMvc
class ReservationApiTest extends RedisIntegrationTestSupport {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private QueueRedisKeys queueKeys;

    @Autowired
    private ReservationRedisKeys reservationKeys;

    @Test
    void activeUserCanClaimSeat() throws Exception {
        redisTemplate.opsForValue().set(queueKeys.active("holiday-2026", "user-1"), Instant.now().toString(), Duration.ofSeconds(60));

        mockMvc.perform(post("/api/events/holiday-2026/reservations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Idempotency-Key", "reserve-1")
                        .content("{\"userId\":\"user-1\",\"seatId\":\"seat-10\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status", is("RESERVED")))
                .andExpect(jsonPath("$.seatId", is("seat-10")));
    }

    @Test
    void inactiveUserGetsNotActive() throws Exception {
        mockMvc.perform(post("/api/events/holiday-2026/reservations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Idempotency-Key", "reserve-1")
                        .content("{\"userId\":\"user-1\",\"seatId\":\"seat-10\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status", is("NOT_ACTIVE")));
    }

    @Test
    void invalidSeatGetsInvalidSeat() throws Exception {
        redisTemplate.opsForValue().set(queueKeys.active("holiday-2026", "user-1"), Instant.now().toString(), Duration.ofSeconds(60));

        mockMvc.perform(post("/api/events/holiday-2026/reservations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Idempotency-Key", "reserve-1")
                        .content("{\"userId\":\"user-1\",\"seatId\":\"seat-9999\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status", is("INVALID_SEAT")));
    }

    @Test
    void duplicateIdempotencyKeyReplaysOriginalResult() throws Exception {
        redisTemplate.opsForValue().set(queueKeys.active("holiday-2026", "user-1"), Instant.now().toString(), Duration.ofSeconds(60));

        mockMvc.perform(post("/api/events/holiday-2026/reservations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Idempotency-Key", "retry-key")
                        .content("{\"userId\":\"user-1\",\"seatId\":\"seat-10\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status", is("RESERVED")))
                .andExpect(jsonPath("$.seatId", is("seat-10")))
                .andExpect(jsonPath("$.message", is("Reserved")));

        mockMvc.perform(post("/api/events/holiday-2026/reservations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Idempotency-Key", "retry-key")
                        .content("{\"userId\":\"user-1\",\"seatId\":\"seat-11\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status", is("RESERVED")))
                .andExpect(jsonPath("$.seatId", is("seat-10")))
                .andExpect(jsonPath("$.message", is("Reserved")));
    }

    @Test
    void differentKeyPreservesOneReservedSeatPerUser() throws Exception {
        redisTemplate.opsForValue().set(queueKeys.active("holiday-2026", "user-1"), Instant.now().toString(), Duration.ofSeconds(60));

        mockMvc.perform(post("/api/events/holiday-2026/reservations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Idempotency-Key", "key-a")
                        .content("{\"userId\":\"user-1\",\"seatId\":\"seat-10\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status", is("RESERVED")))
                .andExpect(jsonPath("$.seatId", is("seat-10")));

        mockMvc.perform(post("/api/events/holiday-2026/reservations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Idempotency-Key", "key-b")
                        .content("{\"userId\":\"user-1\",\"seatId\":\"seat-11\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status", is("ALREADY_RESERVED")))
                .andExpect(jsonPath("$.seatId", is("seat-10")));

        mockMvc.perform(post("/api/events/holiday-2026/reservations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Idempotency-Key", "key-b")
                        .content("{\"userId\":\"user-1\",\"seatId\":\"seat-12\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status", is("ALREADY_RESERVED")))
                .andExpect(jsonPath("$.seatId", is("seat-10")));
    }

    @Test
    void missingIdempotencyKeyReturnsBadRequestWithoutReservation() throws Exception {
        redisTemplate.opsForValue().set(queueKeys.active("holiday-2026", "user-1"), Instant.now().toString(), Duration.ofSeconds(60));

        mockMvc.perform(post("/api/events/holiday-2026/reservations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userId\":\"user-1\",\"seatId\":\"seat-10\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code", is("INVALID_REQUEST")));

        assertNoReservationCreated();
    }

    @Test
    void blankIdempotencyKeyReturnsBadRequestWithoutReservation() throws Exception {
        redisTemplate.opsForValue().set(queueKeys.active("holiday-2026", "user-1"), Instant.now().toString(), Duration.ofSeconds(60));

        mockMvc.perform(post("/api/events/holiday-2026/reservations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Idempotency-Key", " ")
                        .content("{\"userId\":\"user-1\",\"seatId\":\"seat-10\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code", is("INVALID_REQUEST")));

        assertNoReservationCreated();
    }

    @Test
    void overlengthIdempotencyKeyReturnsBadRequestWithoutReservation() throws Exception {
        redisTemplate.opsForValue().set(queueKeys.active("holiday-2026", "user-1"), Instant.now().toString(), Duration.ofSeconds(60));

        mockMvc.perform(post("/api/events/holiday-2026/reservations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Idempotency-Key", "a".repeat(121))
                        .content("{\"userId\":\"user-1\",\"seatId\":\"seat-10\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code", is("BAD_REQUEST")));

        assertNoReservationCreated();
    }

    private void assertNoReservationCreated() {
        org.assertj.core.api.Assertions.assertThat(redisTemplate.hasKey(reservationKeys.seat("holiday-2026", "seat-10"))).isFalse();
        org.assertj.core.api.Assertions.assertThat(redisTemplate.hasKey(reservationKeys.reservationUser("holiday-2026", "user-1"))).isFalse();
    }
}
