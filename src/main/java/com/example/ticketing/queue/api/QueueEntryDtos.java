package com.example.ticketing.queue.api;

import com.example.ticketing.queue.domain.QueueStatus;
import jakarta.validation.constraints.NotBlank;

public final class QueueEntryDtos {

    private QueueEntryDtos() {
    }

    public record QueueEntryRequest(
            @NotBlank(message = "userId is required")
            String userId
    ) {
    }

    public record QueueEntryResponse(
            String queueToken,
            QueueStatus status,
            Long rank,
            Long totalWaiting,
            int pollAfterSeconds
    ) {
    }
}

