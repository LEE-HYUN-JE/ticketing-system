package com.example.ticketing.queue.application;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Redis waiting queue의 사용자를 주기적으로 active 상태로 전환하는 Spring scheduler다.
 *
 * <p>API WAS에서는 {@code queue.scheduler-enabled=false}로 비활성화하고, 별도
 * {@code queue-scheduler} 컨테이너에서만 활성화한다. 이렇게 분리하면 Queue WAS를 scale-out해도
 * admission rate가 WAS 수만큼 곱해지는 실수를 막을 수 있다.</p>
 */
@Component
public class AdmissionScheduler {

    private final AdmissionSchedulerService schedulerService;
    private final QueueProperties properties;

    public AdmissionScheduler(AdmissionSchedulerService schedulerService, QueueProperties properties) {
        this.schedulerService = schedulerService;
        this.properties = properties;
    }

    /**
     * 1초마다 등록된 이벤트 대기열을 확인하고 설정된 수만큼 active admission을 발급한다.
     *
     * <p>{@code fixedRate}를 사용하므로 틱 실행 간격을 처리 완료 시점이 아니라 시작 시점 기준으로 맞춘다.
     * 겹침 가능성은 Redis Lua script의 {@code ZREM} 멱등 가드가 흡수한다.</p>
     */
    @Scheduled(fixedRate = 1000)
    public void admitWaitingUsers() {
        if (properties.schedulerEnabled()) {
            schedulerService.admitAllRegisteredEvents();
        }
    }
}
