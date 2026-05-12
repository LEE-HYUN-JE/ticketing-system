package com.example.ticketing.queue.api.dto;

import com.example.ticketing.queue.domain.QueueStatus;

public record QueueStatusResponse(
        QueueStatus status,
        Long rank,
        Long totalWaiting,
        Integer pollAfterSeconds,
        Long activeExpiresInSeconds
) {
}
