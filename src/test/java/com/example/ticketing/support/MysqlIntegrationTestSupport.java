package com.example.ticketing.support;

/**
 * MySQL이 필요한 통합 테스트용 베이스 클래스.
 * MySQL은 application-test.yml의 Testcontainers JDBC URL(jdbc:tc:mysql:...)로 자동 시작된다.
 */
public abstract class MysqlIntegrationTestSupport extends RedisIntegrationTestSupport {
}
