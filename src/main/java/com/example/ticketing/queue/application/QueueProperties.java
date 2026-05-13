package com.example.ticketing.queue.application;

import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * 대기열 기능의 운영 파라미터를 외부 설정에서 바인딩한다.
 *
 * <p>부하 테스트에서 admission rate, polling 간격, active TTL을 바꿔가며 시스템의 병목을 관찰할 수 있도록
 * 코드 상수가 아니라 configuration properties로 관리한다.</p>
 *
 * @param admissionRatePerSecond 스케줄러가 초당 active로 전환할 최대 사용자 수
 * @param pollAfterSeconds 클라이언트에게 안내할 다음 대기 상태 조회 간격
 * @param activeTtlSeconds active admission이 유지되는 시간
 * @param tokenTtlSeconds queue token이 유지되는 시간
 * @param schedulerEnabled 현재 프로세스에서 admission scheduler를 실행할지 여부
 */
@Validated
@ConfigurationProperties(prefix = "queue")
public record QueueProperties(
        @Min(1) int admissionRatePerSecond,
        @Min(1) int pollAfterSeconds,
        @Min(1) int activeTtlSeconds,
        @Min(1) int tokenTtlSeconds,
        boolean schedulerEnabled
) {
}
