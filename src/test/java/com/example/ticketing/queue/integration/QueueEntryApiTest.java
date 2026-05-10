package com.example.ticketing.queue.integration;

import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.matchesPattern;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.ticketing.support.RedisIntegrationTestSupport;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@AutoConfigureMockMvc
class QueueEntryApiTest extends RedisIntegrationTestSupport {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void enterQueueReturnsWaitingToken() throws Exception {
        mockMvc.perform(post("/api/events/holiday-2026/queue")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userId\":\"user-1\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.queueToken", matchesPattern("[0-9a-fA-F-]{36}")))
                .andExpect(jsonPath("$.status", is("WAITING")))
                .andExpect(jsonPath("$.rank", is(1)))
                .andExpect(jsonPath("$.totalWaiting", is(1)))
                .andExpect(jsonPath("$.pollAfterSeconds", is(5)));
    }

    @Test
    void enterQueueRejectsBlankUserId() throws Exception {
        mockMvc.perform(post("/api/events/holiday-2026/queue")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userId\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code", is("INVALID_REQUEST")));
    }
}

