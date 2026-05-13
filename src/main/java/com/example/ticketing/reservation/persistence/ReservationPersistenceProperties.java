package com.example.ticketing.reservation.persistence;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Redis Stream 기반 비동기 영속화 worker 설정.
 *
 * @param streamKey 예약 성공 이벤트가 쌓이는 Redis Stream key
 * @param consumerGroup worker들이 공유하는 consumer group 이름
 * @param consumerName 현재 worker 인스턴스의 consumer 이름
 * @param batchSize 한 번에 읽을 최대 stream 메시지 수
 * @param pendingIdleMs pending 메시지를 재처리 대상으로 볼 idle 기준 시간
 * @param workerEnabled 현재 프로세스에서 persistence worker를 실행할지 여부
 */
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
