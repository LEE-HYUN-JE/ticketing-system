package com.example.ticketing.reservation.persistence;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "reservation.persistence")
public record ReservationPersistenceProperties(
        String streamKey,
        String consumerGroup,
        String consumerName,
        int batchSize,
        long pendingIdleMs,
        boolean workerEnabled
) {
}
