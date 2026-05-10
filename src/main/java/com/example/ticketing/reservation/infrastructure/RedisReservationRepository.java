package com.example.ticketing.reservation.infrastructure;

import com.example.ticketing.queue.infrastructure.QueueRedisKeys;
import com.example.ticketing.reservation.domain.ReservationModels.ReservationLookupResult;
import com.example.ticketing.reservation.domain.ReservationModels.SeatClaimResult;
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

    @SuppressWarnings("unchecked")
    public SeatClaimResult claimSeat(String eventId, String userId, String seatId, Instant reservedAt) {
        List<String> result = redisTemplate.execute(
                claimSeatScript,
                List.of(
                        queueKeys.active(eventId, userId),
                        reservationKeys.seat(eventId, seatId),
                        reservationKeys.reservationUser(eventId, userId)
                ),
                seatId,
                userId,
                reservedAt.toString()
        );
        if (result == null || result.isEmpty()) {
            throw new IllegalStateException("Seat claim script returned no result");
        }
        ReservationStatus status = ReservationStatus.valueOf(result.get(0));
        String resultSeatId = result.size() > 1 && !result.get(1).isBlank() ? result.get(1) : null;
        String message = result.size() > 2 && !result.get(2).isBlank() ? result.get(2) : null;
        return new SeatClaimResult(status, resultSeatId, message);
    }

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

