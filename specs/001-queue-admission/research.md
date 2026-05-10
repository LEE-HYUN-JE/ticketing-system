# Research: Queue Admission

## Decision: waiting queue는 Redis Sorted Set을 사용한다

**Rationale**: 이 기능은 순서 있는 삽입, 순번 조회, 대기열 길이 조회, 오래된 사용자 우선 제거가 필요하다. Sorted Set은 request time을 score로, user id를 member로 사용하여 이 요구사항을 직접 지원한다.

**Alternatives considered**:

- Redis List: push/pop은 효율적이지만 polling에 필요한 rank 조회가 어렵다.
- RDB 테이블: 관찰은 쉽지만 queue path 제약을 위반하고 트래픽 스파이크 때 write pressure를 만든다.
- Java in-memory queue: 빠르지만 프로세스 재시작에 취약하고 이후 다중 인스턴스 확장에 부적합하다.

## Decision: queue token은 waiting queue membership과 분리해 저장한다

**Rationale**: queue token은 클라이언트가 내부 queue 구조를 알지 않아도 event와 user 상태를 조회하게 해준다. token TTL과 terminal 상태 처리도 waiting queue와 독립적으로 발전시킬 수 있다.

**Alternatives considered**:

- user id를 그대로 token으로 사용: 단순하지만 사용자 식별자를 노출하고 부하 테스트 클라이언트 동작이 덜 현실적이다.
- JWT token: stateless auth에는 유용하지만 이 로컬 기능에는 과하고, Redis에서 무효화하거나 관찰하기 어렵다.

## Decision: active token은 TTL이 있는 Redis key로 관리한다

**Rationale**: active admission은 임시 권한이다. TTL이 있는 Redis key는 입장 후 이탈한 사용자를 자연스럽게 만료시킨다.

**Alternatives considered**:

- active users를 별도 Sorted Set에 저장하고 scheduler로 정리: 분석에는 유연하지만 cleanup 로직이 추가된다.
- TTL 없음: 단순하지만 포기한 active admission이 영원히 남는다.

## Decision: admission은 Spring 내부 scheduler로 시작한다

**Rationale**: 첫 기능은 단일 로컬 Spring Boot 서비스로 실행된다. 내부 scheduler는 설계를 이해하기 쉽고, 백프레셔를 보여주기에 충분하다.

**Alternatives considered**:

- 외부 worker process: 이후 확장에는 유용하지만 첫 기능에는 이르다.
- 애플리케이션 외부 cron: 로컬 실행과 설명이 복잡해진다.

## Decision: Redis 통합 테스트는 Testcontainers를 사용한다

**Rationale**: queue ordering, rank lookup, ZPOPMIN, TTL expiration은 Redis 실제 동작에 의존하므로 실제 Redis 인스턴스 기반 테스트가 필요하다.

**Alternatives considered**:

- Redis template mock: 빠르지만 Redis command semantics를 검증하기 어렵다.
- 공유 local Redis: 편하지만 CI나 다른 개발자 환경에서 재현성이 낮다.
