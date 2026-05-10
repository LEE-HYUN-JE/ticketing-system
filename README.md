# Ticketing Traffic Lab

명절 기차표 예매처럼 특정 시각에 사용자가 한꺼번에 몰리는 상황을 로컬 환경에서 재현하고, 대기열 기반 트래픽 제어와 좌석 선점 정합성을 검증하기 위한 학습용 프로젝트입니다.

이 프로젝트의 목표는 완성된 상용 서비스를 만드는 것이 아니라, **대용량 트래픽을 직접 DB로 보내지 않고 시스템이 처리 가능한 만큼만 흘려보내는 구조**를 설계하고 검증하는 것입니다.

## 시나리오

기준 시나리오는 다음과 같습니다.

- 동시 진입 사용자: 30,000명
- 전체 좌석 수: 2,000석
- 사용자는 예매 시작 시각에 동시에 진입 버튼을 클릭
- 모든 사용자는 Redis 대기열에 등록
- 서버는 초당 설정된 수만큼만 사용자를 예매 가능 상태로 전환
- 입장한 사용자는 5초 동안 무작위 좌석을 선택한다고 가정
- 좌석 선점은 원자적으로 처리
- 성공한 예매만 비동기로 RDB에 저장

## 목표

- 순간적인 대량 진입 요청을 대기열로 흡수한다.
- 실제 예매 트랜잭션 계층에는 감당 가능한 요청만 전달한다.
- 좌석 수보다 많은 예매가 성공하지 않도록 보장한다.
- 동일 사용자의 중복 예매를 방지한다.
- Redis, RDB, API 서버의 병목을 부하 테스트로 관찰한다.
- 다른 개발자가 GitHub에서 구조와 의도를 바로 이해할 수 있도록 문서화한다.

## 다루지 않는 것

- 실제 결제, 회원, 인증, 운영 배포 기능은 다루지 않는다.
- 실제 철도 예매 도메인의 모든 정책을 구현하지 않는다.
- 로컬 테스트 결과를 상용 트래픽 처리량으로 과장하지 않는다.
- 처음부터 Kafka, Kubernetes, Redis Cluster를 도입하지 않는다.

## 아키텍처 요약

핵심 흐름은 다음과 같습니다.

1. 사용자는 예매 진입 요청을 보낸다.
2. Queue API는 사용자를 Redis Sorted Set 대기열에 등록하고 queue token을 발급한다.
3. 사용자는 일정 간격으로 자신의 대기 상태를 조회한다.
4. Admission Scheduler는 초당 설정된 수만큼 대기열에서 사용자를 꺼내 active token을 발급한다.
5. active token을 받은 사용자만 좌석 예매 API를 호출할 수 있다.
6. Reservation API는 idempotency key를 확인하고 Redis Lua Script로 좌석을 원자적으로 선점한다.
7. 성공한 예매 이벤트는 비동기 worker를 통해 MySQL에 저장한다.

자세한 설계는 [docs/architecture.md](docs/architecture.md)를 참고합니다.

## 검증 전략

성능과 정합성은 부하 테스트로 검증합니다.

주요 성공 기준:

- 성공 예매 수는 2,000건을 초과하지 않는다.
- 동일 사용자와 동일 이벤트 조합은 최대 1회만 성공한다.
- active token이 없는 예매 요청은 성공하지 않는다.
- Redis에서 성공한 예매 수와 MySQL에 저장된 예매 수가 일치한다.
- 대기열 상태 조회와 예매 처리는 DB를 직접 압박하지 않는다.

부하 테스트 계획은 [docs/load-test-plan.md](docs/load-test-plan.md)를 참고합니다.

## 예정 기술 스택

- Java 21
- Spring Boot 3
- Redis
- MySQL
- Docker Compose
- Testcontainers
- k6 또는 Gatling

## 개발 방식

이 프로젝트는 SpecKit 기반의 Spec-Driven Development 방식으로 진행합니다.

권장 기능 분해:

1. `001-queue-admission`: 대기열 등록, 순번 조회, active token 전환
2. `002-seat-reservation`: 좌석 조회, 무작위 좌석 선택, Redis Lua 기반 좌석 선점
3. `003-idempotency`: Idempotency-Key 기반 중복 요청 차단
4. `004-async-persistence`: 예매 성공 이벤트 비동기 DB 저장
5. `005-load-test`: 3,000명, 10,000명, 30,000명 부하 테스트
6. `006-observability-docs`: 테스트 결과와 설계 결정 문서화

## 예상 저장소 구조

```text
.
├── docs/
│   ├── architecture.md
│   ├── load-test-plan.md
│   └── adr/
├── load-tests/
├── specs/
├── src/
└── docker-compose.yml
```
