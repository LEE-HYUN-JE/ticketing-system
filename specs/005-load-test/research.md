# Research: 30,000명 Queue-Only 부하 테스트

## Decision: 부하 테스트 범위는 Queue API로 제한한다

**Rationale**: 사용자가 이번 테스트를 30,000명 queue-only로 좁혔다. 이 범위는 대기열 등록, 상태 polling, active admission 전환을 관찰하기에 충분하며, 좌석 선점과 MySQL 영속화 성능을 섞으면 병목 해석이 흐려진다.

**Alternatives considered**:

- 전체 예약 흐름 테스트: 실제 서비스 흐름에 가깝지만, 좌석 선점과 worker 병목이 섞여 queue-only 목적을 흐린다.
- 작은 smoke/baseline 단계 추가: 안정성 확인에는 유용하지만, 사용자가 30,000명 테스트만 진행하기로 범위를 정했다.

## Decision: k6 summary와 Redis CLI 점검을 함께 기록한다

**Rationale**: k6는 HTTP latency와 실패율을 잘 보여주지만 Redis 내부 queue 상태를 직접 설명하지 않는다. Redis key 상태를 함께 기록해야 `waiting`, `active`, `queue-events` 흐름이 실제로 어떻게 남았는지 해석할 수 있다.

**Alternatives considered**:

- k6 output만 저장: 간단하지만 대기열 내부 상태를 설명하기 어렵다.
- Prometheus/Grafana 도입: 관찰성에는 좋지만 이번 기능의 범위를 넘어선다.

## Decision: 블로그 초안은 결과 문서와 분리한다

**Rationale**: 결과 문서는 실험 증거를 정확히 남기는 곳이고, 블로그 초안은 문제 정의와 해석을 독자에게 전달하기 위한 글이다. 둘을 분리하면 숫자와 서사를 각각 깔끔하게 관리할 수 있다.

**Alternatives considered**:

- 결과 문서 하나에 모두 작성: 빠르지만 실험 로그와 게시용 글의 목적이 섞인다.
- 블로그를 나중에 새로 작성: 기억이 흐려지고 실험 당시의 맥락을 놓치기 쉽다.

## Decision: 실패도 결과로 기록한다

**Rationale**: 30,000 VU는 로컬 머신에 부담이 큰 실험이다. 실패가 발생해도 어디서 무너졌는지 기록하면 다음 최적화 방향을 찾는 유효한 근거가 된다.

**Alternatives considered**:

- 성공할 때까지 설정을 낮춘다: 이번 범위인 30,000명 테스트를 훼손한다.
- 실패 결과를 폐기한다: 병목 관찰이라는 프로젝트 목표와 맞지 않는다.
