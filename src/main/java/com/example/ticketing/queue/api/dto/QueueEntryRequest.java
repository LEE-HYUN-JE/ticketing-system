package com.example.ticketing.queue.api.dto;

import jakarta.validation.constraints.NotBlank;

public record QueueEntryRequest(
        @NotBlank(message = "userId is required")
        String userId
) {
}
