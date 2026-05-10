# Quickstart: Queue Admission

이 quickstart는 구현 후 queue admission 기능을 어떻게 실행하고 확인할지 설명합니다.

## 준비물

- Java 21
- Docker
- Docker Compose

## 인프라 실행

```bash
docker compose up -d redis
```

## 테스트 실행

```bash
./gradlew test
```

기대 테스트 범위:

- 중복 queue entry가 중복 waiting position을 만들지 않는다.
- waiting user에게 rank와 total waiting count가 반환된다.
- admission scheduler가 설정된 rate보다 많은 사용자를 입장시키지 않는다.
- active admission이 설정된 TTL 이후 만료된다.
- queue registration과 status lookup은 MySQL을 필요로 하지 않는다.

## 애플리케이션 실행

```bash
./gradlew bootRun
```

## 수동 API 흐름

대기열 진입:

```bash
curl -X POST http://localhost:8080/api/events/holiday-2026/queue \
  -H 'Content-Type: application/json' \
  -d '{"userId":"user-1"}'
```

상태 조회:

```bash
curl http://localhost:8080/api/events/holiday-2026/queue/{queueToken}
```

기대 상태:

- `WAITING`: 사용자가 아직 대기열에 있다.
- `ENTERED`: 사용자가 active admission을 받아 이후 reservation flow로 진행할 수 있다.
- `EXPIRED`: token 또는 active admission을 더 이상 사용할 수 없다.

## 로컬 부하 테스트 Smoke 목표

전체 30,000 virtual-user 시나리오를 추가하기 전에 더 작은 queue-only smoke test를 사용한다.

```text
virtual users: 100
polling interval: 5 seconds
admission rate: 20 users/second
```

중복 사용자가 두 번 대기열에 들어가지 않고, active admission이 설정된 rate를 넘지 않으면 queue 기능은 smoke target을 통과한 것으로 본다.
