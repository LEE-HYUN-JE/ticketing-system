package com.example.ticketing.common.config;

import com.example.ticketing.reservation.persistence.ReservationPersistenceProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration
@EnableScheduling
@EnableConfigurationProperties(ReservationPersistenceProperties.class)
public class PersistenceWorkerConfig {
}
