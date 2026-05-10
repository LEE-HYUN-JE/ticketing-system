# Research: Seat Reservation

## Decision: 좌석 선점은 Redis Lua Script 하나로 처리한다

**Rationale**: 좌석 선점은 active admission 확인, 사용자별 기존 예약 확인, 좌석 점유 확인, 좌석 소유자 기록, 사용자별 예약 결과 기록이 한 번에 성공하거나 실패해야 한다. Redis Lua Script는 단일 Redis 인스턴스 안에서 원자적으로 실행되어 같은 좌석의 중복 선점과 같은 사용자의 다중 좌석 선점을 방지한다.

**Alternatives considered**:

- Java에서 `GET` 후 `SETNX` 여러 번 실행: 구현은 단순하지만 동시 요청 사이에 race condition이 생긴다.
- RDB transaction: 정합성은 만들 수 있지만 이번 기능의 hot path no-MySQL 제약을 위반한다.
- Redis transaction WATCH/MULTI: 가능하지만 Lua보다 조건 분기와 결과 반환을 표현하기 번거롭다.

## Decision: full Idempotency-Key는 후속 기능으로 분리한다

**Rationale**: 이번 기능의 핵심은 좌석 초과 판매와 동일 사용자 다중 좌석 선점을 막는 것이다. full Idempotency-Key는 retry 요청의 동일 응답 재현, processing 상태, TTL 정책까지 포함하므로 별도 feature로 분리하는 편이 작고 검증 가능한 단위가 된다.

**Alternatives considered**:

- 이번 feature에 Idempotency-Key까지 포함: 정합성 범위가 커지고 seat claim의 핵심 검증이 흐려진다.
- idempotency를 전혀 고려하지 않음: constitution의 재시도 변경 요청 규칙과 충돌한다. 따라서 event/user 단위 중복 예매 방지는 이번 feature에 포함한다.

## Decision: 좌석 범위는 `seat-1`부터 `seat-2000`으로 검증한다

**Rationale**: README와 constitution의 기준 시나리오가 2,000석이다. 명시적인 seat id 형식을 두면 잘못된 좌석 요청을 빠르게 거부하고 테스트 데이터를 단순하게 유지할 수 있다.

**Alternatives considered**:

- 좌석 목록을 Redis Set으로 사전 로딩: 실제 서비스에 가깝지만 첫 reservation feature에는 초기화 작업이 추가된다.
- seat id 자유 문자열 허용: 구현은 쉽지만 좌석 수 2,000개 불변조건을 검증하기 어렵다.

## Decision: 예매 결과 조회는 Redis 사용자별 reservation key를 읽는다

**Rationale**: 후속 MySQL 영속화 전에도 사용자의 현재 예약 결과를 관찰할 수 있어야 한다. `reservation:user:{eventId}:{userId}` key는 동일 사용자 중복 예매 방지와 결과 조회를 동시에 지원한다.

**Alternatives considered**:

- 좌석 key 전체 탐색: Redis `KEYS` 금지 원칙과 충돌한다.
- MySQL 조회: 이번 feature 범위와 hot path 제약을 위반한다.

