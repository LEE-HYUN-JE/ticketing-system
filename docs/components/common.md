# Common 컴포넌트

## 역할

`common` 컴포넌트는 queue와 reservation이 함께 사용하는 공통 설정과 에러 응답 규칙을 제공합니다. 비즈니스 로직을 직접 갖기보다는, Redis template, scheduling, configuration properties, API 에러 포맷 같은 횡단 관심사를 담당합니다.

## 폴더 구조

```text
src/main/java/com/example/ticketing/common
├── config
│   ├── PersistenceWorkerConfig.java // scheduling과 persistence 설정 활성화
│   └── RedisConfig.java             // RedisTemplate/StringRedisTemplate 설정
└── error
    ├── ErrorResponse.java           // 공통 에러 응답 DTO
    └── GlobalExceptionHandler.java  // 전역 예외 -> HTTP 에러 응답 변환
```

## 클래스별 책임

### config

| 클래스 | 책임 |
|--------|------|
| `RedisConfig` | `StringRedisTemplate`과 UTF-8 serializer 기반 `RedisTemplate<String, String>` bean을 제공합니다. queue/reservation repository와 worker가 Redis 접근에 사용합니다. |
| `PersistenceWorkerConfig` | `@EnableScheduling`을 활성화하고 `ReservationPersistenceProperties`를 configuration properties로 등록합니다. queue admission scheduler와 reservation persistence worker의 scheduled 동작 기반입니다. |

### error

| 클래스 | 책임 |
|--------|------|
| `ErrorResponse` | API 에러 응답의 공통 형태입니다. `code`, `message`를 담습니다. |
| `GlobalExceptionHandler` | validation 실패, 누락된 header, 잘못된 인자, 예상하지 못한 예외를 HTTP 응답으로 변환합니다. API별 try-catch를 줄이고 에러 응답 형식을 통일합니다. |

## 에러 응답 규칙

| 예외 | HTTP 상태 | code | 설명 |
|------|-----------|------|------|
| `MethodArgumentNotValidException` | 400 | `INVALID_REQUEST` | request body validation 실패 |
| `ConstraintViolationException` | 400 | `INVALID_REQUEST` | path variable, request header 등 method parameter validation 실패 |
| `MissingRequestHeaderException` | 400 | `INVALID_REQUEST` | 필수 header 누락 |
| `IllegalArgumentException` | 400 | `BAD_REQUEST` | 도메인/유스케이스 입력값 오류 |
| 기타 `Exception` | 500 | `INTERNAL_ERROR` | 예상하지 못한 서버 오류 |

## 설정 관점

공통 설정은 `application.yml`의 다음 영역과 연결됩니다.

```text
server.*                     // embedded Tomcat port, thread, connection 설정
spring.data.redis.*          // Redis 연결 설정
reservation.persistence.*    // Redis Stream worker 설정
```

특히 부하 테스트에서는 `server.tomcat.*` 설정이 중요합니다. 1초 10,000건 queue entry 테스트는 Tomcat thread, max connection, accept count를 명시한 뒤 안정적으로 통과했습니다.
