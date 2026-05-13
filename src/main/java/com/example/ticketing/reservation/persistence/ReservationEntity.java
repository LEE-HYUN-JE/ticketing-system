package com.example.ticketing.reservation.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;

/**
 * MySQL에 최종 저장되는 예약 row를 표현한다.
 *
 * <p>Redis가 실시간 좌석 선점을 담당하고, MySQL은 성공한 예약의 최종 영속 저장소 역할을 한다.
 * {@code event_id/user_id}, {@code event_id/seat_id} unique constraint는 Redis Stream 재전달이나 worker 재시작으로
 * 같은 이벤트가 다시 저장되더라도 사용자 중복 예약과 좌석 중복 예약을 마지막으로 차단한다.</p>
 */
@Entity
@Table(
        name = "reservations",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_reservation_event_user", columnNames = {"event_id", "user_id"}),
                @UniqueConstraint(name = "uk_reservation_event_seat", columnNames = {"event_id", "seat_id"})
        }
)
public class ReservationEntity {

    @Id
    private String id;

    @Column(name = "event_id", nullable = false)
    private String eventId;

    @Column(name = "user_id", nullable = false)
    private String userId;

    @Column(name = "seat_id", nullable = false)
    private String seatId;

    @Column(nullable = false)
    private String status;

    @Column(name = "reserved_at", nullable = false)
    private Instant reservedAt;

    @Column(name = "idempotency_key", nullable = false)
    private String idempotencyKey;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected ReservationEntity() {
    }

    /**
     * Redis Stream에서 읽은 예약 성공 이벤트를 MySQL 저장 row로 변환한다.
     *
     * @param id 예약 식별자
     * @param eventId 티켓팅 이벤트 식별자
     * @param userId 사용자 식별자
     * @param seatId 선점된 좌석 식별자
     * @param status 예약 상태
     * @param reservedAt Redis에서 좌석이 선점된 시각
     * @param idempotencyKey 최초 예매 요청의 멱등성 키
     * @param createdAt MySQL row 생성 시각
     */
    public ReservationEntity(String id, String eventId, String userId, String seatId,
                             String status, Instant reservedAt, String idempotencyKey, Instant createdAt) {
        this.id = id;
        this.eventId = eventId;
        this.userId = userId;
        this.seatId = seatId;
        this.status = status;
        this.reservedAt = reservedAt;
        this.idempotencyKey = idempotencyKey;
        this.createdAt = createdAt;
    }

    public String getId() { return id; }
    public String getEventId() { return eventId; }
    public String getUserId() { return userId; }
    public String getSeatId() { return seatId; }
    public String getStatus() { return status; }
    public Instant getReservedAt() { return reservedAt; }
    public String getIdempotencyKey() { return idempotencyKey; }
    public Instant getCreatedAt() { return createdAt; }
}
