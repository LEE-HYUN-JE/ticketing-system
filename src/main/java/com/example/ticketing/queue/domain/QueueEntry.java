package com.example.ticketing.queue.domain;

import java.time.Instant;

/**
 * 사용자가 대기열에 진입한 결과를 나타내는 도메인 값이다.
 *
 * <p>대기열의 실제 순서는 Redis {@code waiting:{eventId}} Sorted Set의 score로 결정되며,
 * {@code queueToken}은 이후 사용자가 자신의 대기 상태를 polling할 때 사용하는 공개 식별자다.</p>
 *
 * @param eventId 티켓팅 이벤트 식별자
 * @param userId 사용자 식별자
 * @param requestedAt 사용자가 대기열 진입을 요청한 시각
 * @param queueToken 대기 상태 조회에 사용할 토큰
 */
public record QueueEntry(String eventId, String userId, Instant requestedAt, String queueToken) {
}
