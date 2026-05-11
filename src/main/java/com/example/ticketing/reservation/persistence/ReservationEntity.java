package com.example.ticketing.reservation.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;

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
