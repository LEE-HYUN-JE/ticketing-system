package com.example.ticketing.queue.application;

import com.example.ticketing.queue.api.dto.QueueEntryResponse;
import com.example.ticketing.queue.domain.QueueStatus;
import com.example.ticketing.queue.infrastructure.RedisQueueRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * 사용자를 대기열에 진입시키는 Queue API의 핵심 유스케이스를 담당한다.
 *
 * <p>이 서비스의 목표는 순간 유입 트래픽을 MySQL로 보내지 않고 Redis에서 흡수하는 것이다.
 * 중복 진입 방지와 queue token 발급은 Redis Lua script가 원자적으로 처리한다.</p>
 */
@Service
public class QueueEntryService {

    private final RedisQueueRepository queueRepository;
    private final QueueProperties properties;
    private final AtomicLong scoreSequence = new AtomicLong();

    public QueueEntryService(RedisQueueRepository queueRepository, QueueProperties properties) {
        this.queueRepository = queueRepository;
        this.properties = properties;
    }

    /**
     * 사용자를 Redis 대기열에 등록하고 polling에 사용할 queue token을 반환한다.
     * 동일 event/user가 반복 진입하면 Lua script가 기존 token으로 수렴시키므로 사용자는 하나의 대기 위치만 가진다.
     *
     * @param eventId 티켓팅 이벤트 식별자
     * @param userId 사용자 식별자
     * @return 클라이언트가 이후 polling에 사용할 queue token과 초기 {@code WAITING} 상태
     * @throws IllegalArgumentException 필수 식별자가 비어 있는 경우
     */
    public QueueEntryResponse enter(String eventId, String userId) {
        validateRequired("eventId", eventId);
        validateRequired("userId", userId);

        String token = registerToken(eventId, userId);
        queueRepository.incrementRegistered();

        return new QueueEntryResponse(
                token,
                QueueStatus.WAITING,
                null,
                null,
                properties.pollAfterSeconds()
        );
    }

    private String registerToken(String eventId, String userId) {
        Instant now = Instant.now();
        String token = UUID.randomUUID().toString();
        return queueRepository.registerQueueEntry(
                token,
                eventId,
                userId,
                now,
                Duration.ofSeconds(properties.tokenTtlSeconds()),
                waitingScore(now)
        );
    }

    /**
     * waiting ZSET score는 요청 시각에 작은 sequence를 더해 만든다.
     * 같은 millisecond에 많은 요청이 들어와도 score 충돌을 줄여 오래 기다린 사용자 순서를 안정적으로 유지한다.
     *
     * @param requestedAt 대기열 진입 요청 시각
     * @return Redis Sorted Set에 저장할 정렬 score
     */
    private double waitingScore(Instant requestedAt) {
        long sequence = scoreSequence.getAndIncrement() % 1_000_000L;
        return requestedAt.toEpochMilli() + (sequence / 1_000_000.0d);
    }

    private static void validateRequired(String field, String value) {
        if (!StringUtils.hasText(value)) {
            throw new IllegalArgumentException(field + " is required");
        }
    }
}
