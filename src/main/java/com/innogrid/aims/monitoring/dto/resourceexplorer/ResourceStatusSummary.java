package com.innogrid.aims.monitoring.dto.resourceexplorer;

import java.util.Collection;

/**
 * 상태 스택바 공용 값 — 총계와 Running/Error/Pending/Stop 카운트.
 * 자원이 없어도 "0/0/0/0"으로 표시하므로 null 없이 항상 값이 내려간다.
 * Namespace·Infra 패널의 목록 행이 사용한다.
 */
public record ResourceStatusSummary(
        long total,
        long running,
        long error,
        long pending,
        long stop
) {
    public static ResourceStatusSummary of(Collection<ResourceStatus> statuses) {
        return new ResourceStatusSummary(
                statuses.size(),
                count(statuses, ResourceStatus.RUNNING),
                count(statuses, ResourceStatus.ERROR),
                count(statuses, ResourceStatus.PENDING),
                count(statuses, ResourceStatus.STOP));
    }

    private static long count(Collection<ResourceStatus> statuses, ResourceStatus status) {
        return statuses.stream().filter(s -> s == status).count();
    }
}
