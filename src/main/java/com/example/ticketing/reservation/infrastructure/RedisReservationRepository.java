package com.example.ticketing.reservation.infrastructure;

import com.example.ticketing.queue.infrastructure.QueueRedisKeys;
import com.example.ticketing.reservation.domain.ReservationLookupResult;
import com.example.ticketing.reservation.domain.SeatClaimResult;
import com.example.ticketing.reservation.domain.ReservationStatus;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.scripting.support.ResourceScriptSource;
import org.springframework.stereotype.Repository;

/**
 * 좌석 선점과 예약 조회를 담당하는 Redis repository다.
 *
 * <p>좌석 예매는 여러 조건(active admission, idempotency, 사용자 중복 예약, 좌석 중복 점유)을 동시에 만족해야 한다.
 * 이 repository는 {@code claim_seat.lua}를 호출해 그 판단과 상태 갱신을 Redis 서버 내부에서 원자적으로 끝낸다.</p>
 */
@Repository
public class RedisReservationRepository {

    private static final String SEAT_ID = "seatId";
    private static final String STATUS = "status";
    private static final String RESERVED_AT = "reservedAt";

    private final StringRedisTemplate redisTemplate;
    private final QueueRedisKeys queueKeys;
    private final ReservationRedisKeys reservationKeys;
    private final DefaultRedisScript<List> claimSeatScript;

    public RedisReservationRepository(
            StringRedisTemplate redisTemplate,
            QueueRedisKeys queueKeys,
            ReservationRedisKeys reservationKeys
    ) {
        this.redisTemplate = redisTemplate;
        this.queueKeys = queueKeys;
        this.reservationKeys = reservationKeys;
        this.claimSeatScript = new DefaultRedisScript<>();
        this.claimSeatScript.setScriptSource(new ResourceScriptSource(new ClassPathResource("lua/claim_seat.lua")));
        this.claimSeatScript.setResultType(List.class);
    }

    /**
     * Redis Lua script로 좌석 선점의 모든 비즈니스 조건을 원자적으로 평가한다.
     * Java 레벨에서 여러 Redis 명령으로 쪼개지 않기 때문에 동시 요청에서도 좌석/사용자/idempotency 상태가 함께 갱신된다.
     *
     * @param eventId 티켓팅 이벤트 식별자
     * @param userId 사용자 식별자
     * @param seatId 선점하려는 좌석 식별자
     * @param idempotencyKey 클라이언트 재시도 중복을 식별하는 키
     * @param reservedAt 좌석 선점 시각
     * @param idempotencyTtlSeconds 결과 replay cache 유지 시간
     * @return Redis Lua script가 계산한 좌석 선점 결과
     */
    @SuppressWarnings("unchecked")
    public SeatClaimResult claimSeat(
            String eventId,
            String userId,
            String seatId,
            String idempotencyKey,
            Instant reservedAt,
            int idempotencyTtlSeconds
    ) {
        // 좌석 선점은 active admission, idempotency replay, 사용자 중복 예매, 좌석 중복 점유를 동시에 판단한다.
        // 여러 Redis 명령으로 나누면 동시 요청 사이에 상태가 바뀔 수 있어 Lua script로 원자 처리한다.
        List<String> result = redisTemplate.execute(
                claimSeatScript,
                List.of(
                        queueKeys.active(eventId, userId),
                        reservationKeys.seat(eventId, seatId),
                        reservationKeys.reservationUser(eventId, userId),
                        reservationKeys.idempotency(eventId, userId, idempotencyKey)
                ),
                seatId,
                userId,
                reservedAt.toString(),
                String.valueOf(idempotencyTtlSeconds)
        );
        if (result == null || result.isEmpty()) {
            throw new IllegalStateException("Seat claim script returned no result");
        }
        ReservationStatus status = ReservationStatus.valueOf(result.get(0));
        String resultSeatId = result.size() > 1 && !result.get(1).isBlank() ? result.get(1) : null;
        String message = result.size() > 2 && !result.get(2).isBlank() ? result.get(2) : null;
        return new SeatClaimResult(status, resultSeatId, message);
    }

    /**
     * Redis에 저장된 사용자별 예매 hash를 조회한다.
     * Redis가 실시간 조회 저장소이고 MySQL은 비동기 최종 저장소이므로 조회 API는 이 값을 우선 사용한다.
     *
     * @param eventId 티켓팅 이벤트 식별자
     * @param userId 사용자 식별자
     * @return Redis에 예약 hash가 있으면 사용자별 예약 결과
     */
    public Optional<ReservationLookupResult> findUserReservation(String eventId, String userId) {
        Map<Object, Object> values = redisTemplate.opsForHash().entries(reservationKeys.reservationUser(eventId, userId));
        if (values.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new ReservationLookupResult(
                ReservationStatus.valueOf(values.get(STATUS).toString()),
                values.get(SEAT_ID).toString(),
                Instant.parse(values.get(RESERVED_AT).toString())
        ));
    }
}
