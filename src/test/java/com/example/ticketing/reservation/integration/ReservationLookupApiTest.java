package com.example.ticketing.reservation.integration;

import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.ticketing.queue.infrastructure.QueueRedisKeys;
import com.example.ticketing.support.RedisIntegrationTestSupport;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@AutoConfigureMockMvc
class ReservationLookupApiTest extends RedisIntegrationTestSupport {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private QueueRedisKeys queueKeys;

    @Test
    void returnsNotReservedBeforeClaim() throws Exception {
        mockMvc.perform(get("/api/events/holiday-2026/reservations/users/user-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status", is("NOT_RESERVED")));
    }

    @Test
    void returnsReservedAfterClaim() throws Exception {
        redisTemplate.opsForValue().set(queueKeys.active("holiday-2026", "user-1"), Instant.now().toString(), Duration.ofSeconds(60));
        mockMvc.perform(post("/api/events/holiday-2026/reservations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userId\":\"user-1\",\"seatId\":\"seat-10\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status", is("RESERVED")));

        mockMvc.perform(get("/api/events/holiday-2026/reservations/users/user-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status", is("RESERVED")))
                .andExpect(jsonPath("$.seatId", is("seat-10")));
    }
}

