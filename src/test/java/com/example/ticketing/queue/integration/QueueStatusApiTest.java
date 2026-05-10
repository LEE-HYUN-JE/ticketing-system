package com.example.ticketing.queue.integration;

import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.ticketing.queue.api.QueueEntryDtos.QueueEntryResponse;
import com.example.ticketing.queue.application.QueueEntryService;
import com.example.ticketing.queue.infrastructure.QueueRedisKeys;
import com.example.ticketing.support.RedisIntegrationTestSupport;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

@AutoConfigureMockMvc
class QueueStatusApiTest extends RedisIntegrationTestSupport {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private QueueEntryService queueEntryService;

    @Autowired
    private QueueRedisKeys keys;

    @Test
    void getQueueStatusReturnsWaiting() throws Exception {
        QueueEntryResponse entry = queueEntryService.enter("holiday-2026", "user-1");

        mockMvc.perform(get("/api/events/holiday-2026/queue/{queueToken}", entry.queueToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status", is("WAITING")))
                .andExpect(jsonPath("$.rank", is(1)))
                .andExpect(jsonPath("$.totalWaiting", is(1)))
                .andExpect(jsonPath("$.pollAfterSeconds", is(5)));
    }

    @Test
    void getQueueStatusReturnsEntered() throws Exception {
        QueueEntryResponse entry = queueEntryService.enter("holiday-2026", "user-1");
        redisTemplate.opsForZSet().remove(keys.waiting("holiday-2026"), "user-1");
        redisTemplate.opsForValue().set(keys.active("holiday-2026", "user-1"), Instant.now().toString(), Duration.ofSeconds(60));

        mockMvc.perform(get("/api/events/holiday-2026/queue/{queueToken}", entry.queueToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status", is("ENTERED")))
                .andExpect(jsonPath("$.activeExpiresInSeconds", notNullValue()));
    }

    @Test
    void getQueueStatusReturnsExpiredForUnknownToken() throws Exception {
        mockMvc.perform(get("/api/events/holiday-2026/queue/{queueToken}", UUID.randomUUID()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status", is("EXPIRED")));
    }

    @Test
    void getQueueStatusRejectsInvalidUuid() throws Exception {
        mockMvc.perform(get("/api/events/holiday-2026/queue/not-a-uuid"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code", is("BAD_REQUEST")));
    }

    @Test
    void getQueueStatusRejectsEventMismatch() throws Exception {
        QueueEntryResponse entry = queueEntryService.enter("holiday-2026", "user-1");

        mockMvc.perform(get("/api/events/other-event/queue/{queueToken}", entry.queueToken()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code", is("BAD_REQUEST")));
    }
}

