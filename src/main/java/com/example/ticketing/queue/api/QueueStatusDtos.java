package com.example.ticketing.queue.api;

import com.example.ticketing.queue.domain.QueueStatus;

public final class QueueStatusDtos {

    private QueueStatusDtos() {
    }

    public record QueueStatusResponse(
            QueueStatus status,
            Long rank,
            Long totalWaiting,
            Integer pollAfterSeconds,
            Long activeExpiresInSeconds
    ) {
    }
}

