package com.example.ticketing.queue.api.dto;

import com.example.ticketing.queue.domain.QueueStatus;

public record QueueEntryResponse(
        String queueToken,
        QueueStatus status,
        Long rank,
        Long totalWaiting,
        int pollAfterSeconds
) {
}
