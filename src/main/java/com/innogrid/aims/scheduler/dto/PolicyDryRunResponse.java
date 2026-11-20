package com.innogrid.aims.scheduler.dto;

import java.time.OffsetDateTime;
import java.util.List;

import com.innogrid.aims.scheduler.entity.Workload;
import com.innogrid.aims.scheduler.model.SchedulingResult;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 정책 수정 전 모의 배치 응답 — 같은 수집 한 번 위에서 저장된 정책과 고친 정책으로 워크로드 초안 하나를 각각 놓아 본 결과.
 * 아무것도 저장하지 않는다. 시각은 UTC다.
 */
@Schema(description = "정책 수정 전 모의 배치 — 같은 수집 한 번 위에서 저장된 정책(current)과 고친 정책(edited)으로 워크로드 초안 하나를 "
        + "각각 놓아 본 결과. 아무것도 저장하지 않습니다")
public record PolicyDryRunResponse(
        @Schema(description = "계산 시각(UTC)", example = "2026-11-23T03:00:00Z")
        OffsetDateTime evaluatedAt,
        @Schema(description = "고쳐 본 정책 id", example = "1")
        Long policyId,
        @Schema(description = "고쳐 본 정책 이름", example = "default")
        String policyName,
        @Schema(description = "이 초안의 리소스 종류에 지금 실제 배치가 쓰는 정책이 이 정책인지(종류 정책 → 공통 정책 → default 순으로 고른 "
                + "결과와 id 가 같음). false 면 이 결과는 지금 배치와 무관합니다", example = "true")
        boolean policyInUse,
        @Schema(description = "초안의 리소스 종류(비우면 VM)", example = "VM")
        Workload.ResourceType resourceType,
        @Schema(description = "저장된 값과 달라진 판단 필드 이름(고정 순서: defaultEngine · warningTemperatureCelsius · "
                + "cpuWeight · memoryWeight · powerWeight · temperatureWeight · networkWeight · maxCpuUsagePercent · "
                + "maxMemoryUsagePercent · maxTemperatureCelsius · maxPowerUsagePercent · powerAwareEnabled · "
                + "temperatureAwareEnabled · affinityEnabled). 같은 값을 보내면 들어가지 않습니다",
                example = "[\"maxCpuUsagePercent\"]")
        List<String> changedFields,
        @Schema(description = "저장된 정책으로 놓을 때 쓴 엔진", example = "weighted")
        String currentEngine,
        @Schema(description = "저장된 정책으로 놓아 본 결과 — 저장 전 모의 배치(POST /workloads/dry-run)와 같은 일곱 키")
        SchedulingResult current,
        @Schema(description = "고친 정책으로 놓을 때 쓴 엔진", example = "power")
        String editedEngine,
        @Schema(description = "고친 정책으로 놓아 본 결과 — current 와 같은 일곱 키 · policyName 은 저장된 정책 이름")
        SchedulingResult edited,
        @Schema(description = "두 결과의 성공 여부나 고른 노드가 다른지", example = "true")
        boolean decisionChanged,
        @Schema(description = "이 계산의 한계(고정 문장)")
        String limitation
) {
    public PolicyDryRunResponse {
        changedFields = changedFields == null ? List.of() : List.copyOf(changedFields);
    }
}
