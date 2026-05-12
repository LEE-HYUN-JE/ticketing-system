# Queue 컴포넌트

## 역할

`queue` 컴포넌트는 대량 진입 요청을 바로 예매 처리로 보내지 않고 Redis 대기열에 흡수하는 계층입니다. 사용자는 먼저 queue token을 받고, scheduler가 초당 설정된 수만큼만 사용자를 `ENTERED` 상태로 전환합니다.

핵심 책임:

- 사용자 대기열 진입 요청 처리
- queue token 발급 및 중복 진입 token 재사용
- 현재 대기 상태, 순번, active TTL 조회
- 대기열에서 active admission으로 초당 제한 전환
- active token이 있는 사용자만 예매 API를 호출하도록 보호
- 대기열 운영 지표 집계

## 주요 흐름

```text
POST /api/events/{eventId}/queue
  -> QueueController
  -> QueueEntryService
  -> RedisQueueRepository
  -> register_queue_entry.lua
  -> queueToken 반환

GET /api/events/{eventId}/queue/{queueToken}
  -> QueueController
  -> QueueStatusService
  -> RedisQueueRepository
  -> WAITING / ENTERED / EXPIRED 반환

AdmissionScheduler 매 1초
  -> AdmissionSchedulerService
  -> RedisAdmissionRepository
  -> admit_waiting_users.lua
  -> waiting 사용자 일부를 active 상태로 전환
```

## 폴더 구조

```text
src/main/java/com/example/ticketing/queue
├── api
│   ├── QueueController.java      // Queue HTTP API 진입점
│   └── dto
│       ├── QueueEntryRequest.java  // 대기열 진입 요청 DTO
│       ├── QueueEntryResponse.java // 대기열 진입 응답 DTO
│       └── QueueStatusResponse.java // 대기열 상태 조회 응답 DTO
├── application
│   ├── ActiveAdmissionGuard.java // active token 검증 가드
│   ├── AdmissionScheduler.java   // 1초 주기 입장 스케줄러 트리거
│   ├── AdmissionSchedulerService.java // waiting -> active 전환 유스케이스
│   ├── QueueEntryService.java    // 대기열 진입 유스케이스
│   ├── QueueMetricsService.java  // 대기열 지표 스냅샷 집계
│   ├── QueueProperties.java      // queue.* 설정 바인딩
│   └── QueueStatusService.java   // token 기반 대기 상태 조회 유스케이스
├── domain
│   ├── QueueModels.java          // queue 도메인 record 모음
│   └── QueueStatus.java          // WAITING / ENTERED / EXPIRED 상태 enum
└── infrastructure
    ├── QueueRedisKeys.java       // Redis key 네이밍 규칙
    ├── RedisAdmissionRepository.java // Lua 기반 입장 전환 Redis 접근
    └── RedisQueueRepository.java // queue token, waiting, active, metrics Redis 접근
```

## 클래스별 책임

### api

| 클래스 | 책임 |
|--------|------|
| `QueueController` | `/api/events/{eventId}/queue` 하위 HTTP API를 노출합니다. `POST`는 대기열 진입, `GET /{queueToken}`은 상태 조회로 위임합니다. |
| `dto.QueueEntryRequest` | 대기열 진입 요청 body입니다. 현재는 `userId`만 받습니다. |
| `dto.QueueEntryResponse` | 대기열 진입 응답입니다. hot path 최적화를 위해 `POST /queue`에서는 `rank`, `totalWaiting`을 `null`로 반환하고 token 중심으로 응답합니다. |
| `dto.QueueStatusResponse` | 상태 조회 응답입니다. `WAITING`이면 순번과 전체 대기 수, `ENTERED`이면 active TTL, `EXPIRED`이면 빈 상태 정보를 반환합니다. |

### application

| 클래스 | 책임 |
|--------|------|
| `QueueEntryService` | 대기열 진입 유스케이스를 처리합니다. 입력값을 검증하고 Redis Lua script를 통해 queue token을 원자적으로 등록한 뒤 `WAITING` 응답을 반환합니다. |
| `QueueStatusService` | queue token을 검증하고 token mapping, waiting ZSET, active TTL을 조회해 `WAITING`, `ENTERED`, `EXPIRED`를 판정합니다. |
| `AdmissionScheduler` | `@Scheduled(fixedRate = 1000)`로 매초 입장 전환을 트리거합니다. `queue.scheduler-enabled`가 꺼져 있으면 동작하지 않습니다. |
| `AdmissionSchedulerService` | 등록된 모든 event를 순회하며 `admission-rate-per-second`만큼 waiting 사용자를 active 상태로 이동합니다. |
| `ActiveAdmissionGuard` | 예매 API 진입 전에 사용자가 active admission을 보유했는지 검증합니다. active 상태가 아니면 예매를 막습니다. |
| `QueueMetricsService` | 등록 수, 입장 수, 현재 waiting 수, active 수, 만료 조회 수를 Redis에서 집계합니다. |
| `QueueProperties` | `queue.admission-rate-per-second`, `poll-after-seconds`, `active-ttl-seconds`, `token-ttl-seconds`, `scheduler-enabled` 설정을 바인딩합니다. |

### domain

| 클래스 | 책임 |
|--------|------|
| `QueueModels.QueueEntry` | 대기열 진입 도메인 데이터 표현입니다. |
| `QueueModels.QueueTokenMapping` | token이 어떤 `eventId/userId`에 속하는지 표현합니다. |
| `QueueModels.ActiveAdmission` | active admission의 event, user, 입장 시각, 만료 시각을 표현합니다. |
| `QueueModels.QueuePosition` | waiting ZSET 기준 현재 순번과 전체 대기 수를 표현합니다. |
| `QueueStatus` | queue 상태 enum입니다. `WAITING`, `ENTERED`, `EXPIRED`를 가집니다. |

### infrastructure

| 클래스 | 책임 |
|--------|------|
| `QueueRedisKeys` | queue 관련 Redis key를 한곳에서 생성합니다. key prefix가 흩어지지 않도록 관리합니다. |
| `RedisQueueRepository` | queue token mapping, reverse index, waiting ZSET, active TTL, metrics를 Redis에 읽고 씁니다. 대기열 진입은 `register_queue_entry.lua`로 원자 처리합니다. |
| `RedisAdmissionRepository` | `admit_waiting_users.lua`를 실행해 오래 기다린 사용자부터 active 상태로 이동합니다. |

## Redis 자료구조

```text
queue-user-token:{eventId}:{userId}     // event/user -> queueToken reverse index
queue-token:{token}                     // token -> eventId/userId/createdAt hash
waiting:{eventId}                       // 대기 사용자 Sorted Set
active:{eventId}:{userId}               // active admission TTL key
active-users:{eventId}                  // active 사용자 추적 Sorted Set
queue-events                            // scheduler가 순회할 event set
queue-metrics:registered                // 대기열 등록 요청 수
queue-metrics:admitted                  // active로 입장 처리된 수
queue-metrics:expired-lookup            // 만료/무효 token 조회 수
```

## Lua script

| 파일 | 역할 |
|------|------|
| `register_queue_entry.lua` | 동일 `eventId/userId` 중복 진입을 기존 token 반환으로 수렴시키고, 신규 진입이면 token mapping과 waiting ZSET 등록을 원자 처리합니다. |
| `admit_waiting_users.lua` | waiting ZSET에서 오래 기다린 사용자부터 꺼내 active TTL key와 active-users ZSET에 기록합니다. |

## 테스트 관점

queue 컴포넌트는 다음을 중심으로 검증합니다.

- 신규 진입 시 queue token 발급
- 동일 event/user 중복 진입 시 같은 token 반환
- 동일 event/user 동시 100개 요청 시 token 1개와 waiting member 1개로 수렴
- 상태 조회 시 `WAITING`, `ENTERED`, `EXPIRED` 판정
- scheduler가 설정된 rate만큼 waiting 사용자를 active로 이동
- active admission이 없는 예매 요청 차단
