CREATE TABLE IF NOT EXISTS reservations
(
    id              VARCHAR(36)  NOT NULL,
    event_id        VARCHAR(255) NOT NULL,
    user_id         VARCHAR(255) NOT NULL,
    seat_id         VARCHAR(255) NOT NULL,
    status          VARCHAR(50)  NOT NULL,
    reserved_at     DATETIME(6)  NOT NULL,
    idempotency_key VARCHAR(120) NOT NULL,
    created_at      DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uk_reservation_event_user (event_id, user_id),
    UNIQUE KEY uk_reservation_event_seat (event_id, seat_id),
    INDEX idx_reservation_event_id (event_id)
);
