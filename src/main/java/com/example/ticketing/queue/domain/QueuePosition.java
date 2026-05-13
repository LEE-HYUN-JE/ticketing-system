package com.example.ticketing.queue.domain;

/**
 * 대기열에 남아 있는 사용자의 현재 위치를 표현한다.
 *
 * <p>{@code rank}는 사용자에게 보여주는 1-based 순번이다. Redis {@code ZRANK}는 0-based 값을 반환하므로
 * repository 경계에서 사용자 친화적인 값으로 변환해 이 타입에 담는다.</p>
 *
 * @param rank 현재 사용자의 대기 순번
 * @param totalWaiting 해당 이벤트의 전체 대기 인원
 */
public record QueuePosition(long rank, long totalWaiting) {
}
