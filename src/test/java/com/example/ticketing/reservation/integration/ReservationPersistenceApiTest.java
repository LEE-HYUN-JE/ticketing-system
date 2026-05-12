package com.example.ticketing.reservation.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.ticketing.queue.infrastructure.QueueRedisKeys;
import com.example.ticketing.reservation.persistence.ReservationJpaRepository;
import com.example.ticketing.reservation.persistence.ReservationPersistenceWorker;
import com.example.ticketing.support.RedisIntegrationTestSupport;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.context.TestPropertySource;

@AutoConfigureMockMvc
@TestPropertySource(properties = "reservation.persistence.worker-enabled=true")
class ReservationPersistenceApiTest extends RedisIntegrationTestSupport {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private QueueRedisKeys queueKeys;

    @Autowired
    private ReservationJpaRepository jpaRepository;

    @Autowired
    private ReservationPersistenceWorker worker;

    @BeforeEach
    void setUp() {
        jpaRepository.deleteAll();
        worker.initConsumerGroup();
    }

    @Test
    void successfulReservationIsPersistedToDb() throws Exception {
        redisTemplate.opsForValue().set(
                queueKeys.active("event-1", "user-1"),
                Instant.now().toString(),
                Duration.ofSeconds(60)
        );

        mockMvc.perform(post("/api/events/event-1/reservations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Idempotency-Key", "idem-persist-1")
                        .content("{\"userId\":\"user-1\",\"seatId\":\"seat-1\"}"))
                .andExpect(status().isOk());

        worker.processOnce();

        assertThat(jpaRepository.findAll())
                .anyMatch(r -> r.getUserId().equals("user-1")
                        && r.getSeatId().equals("seat-1")
                        && r.getEventId().equals("event-1"));
    }

    @Test
    void sameUserCannotHaveTwoReservationsInDb() throws Exception {
        redisTemplate.opsForValue().set(
                queueKeys.active("event-2", "user-2"),
                Instant.now().toString(),
                Duration.ofSeconds(60)
        );

        mockMvc.perform(post("/api/events/event-2/reservations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Idempotency-Key", "idem-persist-2a")
                        .content("{\"userId\":\"user-2\",\"seatId\":\"seat-2\"}"))
                .andExpect(status().isOk());

        worker.processOnce();

        assertThat(jpaRepository.findAll()).hasSize(1);
    }
}
