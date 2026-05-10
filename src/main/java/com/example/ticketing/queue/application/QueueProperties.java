package com.example.ticketing.queue.application;

import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "queue")
public record QueueProperties(
        @Min(1) int admissionRatePerSecond,
        @Min(1) int pollAfterSeconds,
        @Min(1) int activeTtlSeconds,
        @Min(1) int tokenTtlSeconds
) {
}

