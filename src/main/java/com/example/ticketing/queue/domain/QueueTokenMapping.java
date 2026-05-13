package com.example.ticketing.queue.domain;

import java.time.Instant;

/**
 * 클라이언트가 들고 있는 queue token을 내부 event/user 식별자로 복원한 결과다.
 *
 * <p>API 서버는 세션을 들고 있지 않으며, Redis {@code queue-token:{token}} Hash를 조회해
 * 이 매핑을 복원한다. 이 덕분에 여러 Queue WAS 중 어느 인스턴스가 polling 요청을 받아도 같은 상태를 계산할 수 있다.</p>
 *
 * @param token 클라이언트에게 발급한 queue token
 * @param eventId token이 속한 이벤트 식별자
 * @param userId token 소유 사용자 식별자
 * @param createdAt token 생성 시각
 */
public record QueueTokenMapping(String token, String eventId, String userId, Instant createdAt) {
}
