package com.innogrid.aims.scheduler.dto;

import tools.jackson.databind.JsonNode;

/**
 * 배치 정책 생성·수정 요청.
 *
 * <p>수정(PUT)은 <b>부분 수정</b>이다 — 요청에 있는 키만 반영하고 없는 키는 유지한다. 예전 맵 방식이
 * 그랬고, 화면도 그렇게 쓴다. 그래서 대부분의 필드는 boxed 타입이며 null(=키 없음)이면 건드리지 않는다.</p>
 *
 * <p>세 필드는 "비우는 것"에도 의미가 있다 — {@code resourceType} 에 명시적 null 은 <b>공통 정책으로 전환</b>,
 * {@code defaultEngine} null 은 기본 엔진(weighted)으로, {@code warningTemperatureCelsius} null 은 설정 기본값으로.
 * record 의 일반 필드는 "키 없음"과 "명시적 null" 을 구분하지 못하므로({@code Optional} 도 못 한다 —
 * {@code PartialUpdateNullSemanticsTest} 로 실측) 이 셋만 {@link JsonNode} 로 받는다:
 * 필드가 {@code null} 이면 키 없음(유지), {@code isNull()} 이면 명시적 null(비움), 아니면 값.</p>
 *
 * <p>검증은 {@code SchedulingPolicyService} 가 한다. 가중치는 <b>비율로만</b> 쓰이므로 합이 1 이 아니어도 된다 —
 * 음수와 "전부 0" 만 막는다.</p>
 */
public record SchedulingPolicyRequest(
        String name,
        String description,

        JsonNode resourceType,
        JsonNode defaultEngine,
        JsonNode warningTemperatureCelsius,

        Double cpuWeight,
        Double memoryWeight,
        Double powerWeight,
        Double temperatureWeight,
        Double networkWeight,

        Double maxCpuUsagePercent,
        Double maxMemoryUsagePercent,
        Double maxTemperatureCelsius,
        Double maxPowerUsagePercent,

        Boolean powerAwareEnabled,
        Boolean temperatureAwareEnabled,
        /** MDC 링크 반영 게이트. 켜면 dataMdcId 가 있는 워크로드에 C5 와 링크 점수가 적용된다. 응답에는 이미 있던 필드다. */
        Boolean affinityEnabled,
        Boolean active
) {}
