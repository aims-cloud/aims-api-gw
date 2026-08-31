package com.innogrid.aims.scheduler.dto;

import com.innogrid.aims.scheduler.entity.Workload;

/**
 * 스케줄 생성/편집 요청 — 스케줄러 생성 화면 기준.
 *
 * <p>앞의 7필드가 화면에 있는 항목이고, 뒤의 4필드는 명세서(연구계획)가 요구하는 확장이다.
 * 확장 필드는 전부 선택이라 화면 폼만으로 만들어도 동작한다 — 비워 두면 리소스 종류별
 * 활성 정책과 그 정책의 기본 엔진을 쓴다.</p>
 *
 * <p>검증은 서비스 계층에서 한다. 저장소의 최신 DTO 관례를 따라 검증 애너테이션을 두지
 * 않았고, 그래야 오류가 400 으로 일관되게 나간다.</p>
 */
public record ScheduleRequest(
        String scheduleId,                  // 업무 키. 생성 시에만 쓰고 편집에서는 무시한다
        String name,
        Workload.ResourceType resourceType, // 비우면 VM
        String cronExpression,              // Quartz 표기. 한국 시간으로 해석한다
        Boolean autoRun,                    // 비우면 false
        Boolean enabled,                    // 비우면 true
        String description,

        String engine,                      // weighted | power | temperature. 비우면 정책 기본값
        Long policyId,                      // 비우면 리소스 종류별 활성 정책
        Double maxPowerBudgetWatts,
        Double maxTemperatureCelsius
) {
    public boolean resolveAutoRun() { return autoRun != null && autoRun; }

    public boolean resolveEnabled() { return enabled == null || enabled; }
}
