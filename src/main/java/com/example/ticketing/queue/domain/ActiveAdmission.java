package com.example.ticketing.queue.domain;

import java.time.Instant;

/**
 * 대기열을 통과해 예매 API를 호출할 수 있게 된 사용자의 active admission 상태를 표현한다.
 *
 * <p>실제 런타임 권한은 Redis의 {@code active:{eventId}:{userId}} TTL key가 담당하지만,
 * 이 타입은 테스트나 애플리케이션 계층에서 active 진입 시각과 만료 시각을 명시적으로 다룰 때 사용한다.</p>
 *
 * @param eventId 티켓팅 이벤트 식별자
 * @param userId 사용자 식별자
 * @param enteredAt active 상태로 전환된 시각
 * @param expiresAt 예매 가능 창이 만료되는 시각
 */
public record ActiveAdmission(String eventId, String userId, Instant enteredAt, Instant expiresAt) {
}
