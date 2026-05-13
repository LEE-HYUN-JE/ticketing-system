package com.example.ticketing.reservation.domain;

/**
 * 좌석 예매와 예매 조회에서 사용하는 비즈니스 상태.
 *
 * <p>Redis Lua script의 반환값과 API 응답 상태가 이 enum으로 수렴한다.
 * 실패 상태도 명시적으로 표현해 클라이언트가 재시도 가능 여부를 판단할 수 있게 한다.</p>
 */
public enum ReservationStatus {
    /** 좌석 선점 성공. 같은 멱등성 키 재시도에서 재생된 성공도 이 상태를 사용한다. */
    RESERVED,

    /** active admission 없이 예매를 시도했거나 active TTL이 만료된 상태. */
    NOT_ACTIVE,

    /** 요청한 좌석을 다른 사용자가 이미 선점한 상태. */
    SEAT_ALREADY_TAKEN,

    /** 사용자가 같은 이벤트에서 이미 다른 좌석을 예약한 상태. */
    ALREADY_RESERVED,

    /** 사용자 기준 예약 정보가 존재하지 않는 조회 결과. */
    NOT_RESERVED,

    /** 좌석 ID 형식이나 범위가 현재 이벤트의 좌석 정책에 맞지 않는 상태. */
    INVALID_SEAT
}
