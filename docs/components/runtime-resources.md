# Runtime Resources

## 역할

`src/main/resources`는 애플리케이션 실행 설정, Redis Lua script, MySQL schema를 담습니다. 이 프로젝트는 고동시성 경로에서 Redis Lua script를 적극 사용하므로, Java 클래스만큼 resource 파일도 핵심 설계 요소입니다.

## 폴더 구조

```text
src/main/resources
├── application.yml              // 애플리케이션, Redis, MySQL, queue, reservation 설정
├── schema.sql                   // MySQL reservations 테이블 DDL
└── lua
    ├── admit_waiting_users.lua  // waiting -> active admission 전환
    ├── claim_seat.lua           // 좌석 선점 원자 처리
    └── register_queue_entry.lua // 대기열 진입 원자 처리
```

## 파일별 책임

| 파일 | 책임 |
|------|------|
| `application.yml` | 서버 포트, Tomcat 수용 설정, MySQL 연결, Redis 연결, queue rate/TTL, reservation/idempotency/persistence 설정을 관리합니다. |
| `schema.sql` | MySQL `reservations` 테이블과 unique constraint를 생성합니다. 비동기 저장 중 event/user, event/seat 중복 저장을 방지합니다. |
| `lua/register_queue_entry.lua` | queue entry hot path에서 token 등록, reverse index, waiting ZSET 등록, event registry 등록을 원자 처리합니다. |
| `lua/admit_waiting_users.lua` | scheduler가 호출하며 waiting ZSET에서 오래 기다린 사용자를 active TTL key와 active-users ZSET으로 이동합니다. |
| `lua/claim_seat.lua` | active admission 검증, idempotency replay, 사용자 중복 예매 방지, 좌석 중복 선점 방지를 원자 처리합니다. |

## application.yml 주요 설정

```yaml
server:
  tomcat:
    threads:
      max: 400
      min-spare: 50
    max-connections: 20000
    accept-count: 10000
    connection-timeout: 5s
```

위 설정은 1초 10,000건 queue entry 부하 테스트를 통과하기 위해 명시한 WAS 수용 계층 설정입니다.

```yaml
queue:
  admission-rate-per-second: 300
  poll-after-seconds: 5
  active-ttl-seconds: 60
  token-ttl-seconds: 3600
  scheduler-enabled: true
```

queue scheduler는 매초 등록된 event를 순회하며 `admission-rate-per-second`만큼 사용자를 active 상태로 이동합니다.

```yaml
reservation:
  seat-capacity: 2000
  seat-id-prefix: seat-
  idempotency-ttl-seconds: 600
  idempotency-key-max-length: 120
  persistence:
    stream-key: reservation-events
    consumer-group: persistence-workers
    consumer-name: worker-1
    batch-size: 10
    pending-idle-ms: 30000
    worker-enabled: true
```

reservation 설정은 좌석 수, 좌석 ID prefix, idempotency TTL, Redis Stream persistence worker를 제어합니다.

## Lua script 사용 이유

이 프로젝트에서 Lua script를 사용하는 이유는 두 가지입니다.

1. 여러 Redis 명령을 하나의 원자 작업으로 묶어 race condition을 줄입니다.
2. 애플리케이션과 Redis 사이 round-trip을 줄여 hot path를 단순화합니다.

예를 들어 queue entry는 기존 token 확인과 신규 token 등록이 분리되면 동시 중복 요청에서 race condition이 생길 수 있습니다. `register_queue_entry.lua`는 이를 하나의 Redis script로 묶어 같은 `eventId/userId`가 항상 하나의 token으로 수렴하게 만듭니다.

## 문서와 연결된 실험

- [1초 10,000건 Queue Entry 부하 테스트 통과 결과](../load-test-results/005-fast-queue-entry-10000-1s.md)
- [1초 30,000건 Queue Entry 부하 테스트 한계 결과](../load-test-results/005-fast-queue-entry-30000-1s.md)
