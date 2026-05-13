package com.example.ticketing.queue.domain;

/**
 * queue token으로 조회할 수 있는 대기열 상태.
 *
 * <p>상태 전이는 {@code WAITING -> ENTERED -> EXPIRED} 흐름을 기본으로 한다.
 * {@code ENTERED}는 좌석 예매를 시도할 수 있는 active TTL key가 아직 살아 있는 상태다.</p>
 */
public enum QueueStatus {
    /** 아직 waiting ZSET에 남아 있어 대기 순번을 반환할 수 있는 상태. */
    WAITING,

    /** 스케줄러가 active token을 발급해 예매 API 호출이 가능한 상태. */
    ENTERED,

    /** queue token이 만료됐거나 active 예매 가능 창이 닫힌 상태. */
    EXPIRED
}
