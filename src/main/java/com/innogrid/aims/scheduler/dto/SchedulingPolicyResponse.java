package com.innogrid.aims.scheduler.dto;

import java.time.OffsetDateTime;

import com.innogrid.aims.scheduler.entity.SchedulingPolicy;
import com.innogrid.aims.scheduler.entity.Workload;

/**
 * 배치 정책 응답 — {@code /scheduler/policies} 4종이 공유한다.
 *
 * <p>필드 20개는 {@link SchedulingPolicy} 엔티티를 그대로 직렬화한 결과와 <b>동일</b>하다
 * ({@code ResponseJsonGoldenTest}가 확인). 엔티티의 {@code isXxx()}는 Jackson이 {@code xxx}로
 * 내보내므로 record의 {@code boolean xxx}와 이름이 맞는다.</p>
 *
 * <p>가중치는 비율로만 쓰인다 — 합계가 1이 아니어도 된다. 그래서 정규화하지 않고 저장값을 그대로 낸다.</p>
 */
public record SchedulingPolicyResponse(
        Long id,
        String name,
        String description,
        Workload.ResourceType resourceType,
        String defaultEngine,
        Double warningTemperatureCelsius,
        double cpuWeight,
        double memoryWeight,
        double powerWeight,
        double temperatureWeight,
        double networkWeight,
        double maxCpuUsagePercent,
        double maxMemoryUsagePercent,
        double maxTemperatureCelsius,
        double maxPowerUsagePercent,
        boolean powerAwareEnabled,
        boolean temperatureAwareEnabled,
        boolean affinityEnabled,
        boolean active,
        OffsetDateTime createdAt
) {
    public static SchedulingPolicyResponse from(SchedulingPolicy p) {
        return new SchedulingPolicyResponse(
                p.getId(), p.getName(), p.getDescription(), p.getResourceType(), p.getDefaultEngine(),
                p.getWarningTemperatureCelsius(),
                p.getCpuWeight(), p.getMemoryWeight(), p.getPowerWeight(), p.getTemperatureWeight(), p.getNetworkWeight(),
                p.getMaxCpuUsagePercent(), p.getMaxMemoryUsagePercent(), p.getMaxTemperatureCelsius(), p.getMaxPowerUsagePercent(),
                p.isPowerAwareEnabled(), p.isTemperatureAwareEnabled(), p.isAffinityEnabled(), p.isActive(),
                p.getCreatedAt());
    }
}
