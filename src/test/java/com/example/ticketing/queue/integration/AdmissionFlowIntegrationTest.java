package com.example.ticketing.queue.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.ticketing.queue.application.AdmissionSchedulerService;
import com.example.ticketing.support.RedisIntegrationTestSupport;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@AutoConfigureMockMvc
class AdmissionFlowIntegrationTest extends RedisIntegrationTestSupport {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private AdmissionSchedulerService schedulerService;

    @Test
    void userCanObserveEnteredStatusAfterAdmission() throws Exception {
        String body = mockMvc.perform(post("/api/events/holiday-2026/queue")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userId\":\"user-1\"}"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        String token = body.replaceAll(".*\"queueToken\":\"([^\"]+)\".*", "$1");

        assertThat(schedulerService.admitOneTick("holiday-2026")).containsExactly("user-1");

        mockMvc.perform(get("/api/events/holiday-2026/queue/{queueToken}", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status", is("ENTERED")));
    }
}

