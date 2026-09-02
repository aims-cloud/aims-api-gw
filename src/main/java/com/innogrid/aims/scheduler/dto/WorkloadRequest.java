package com.innogrid.aims.scheduler.dto;

/**
 * 워크로드 생성 요청.
 *
 * <p>예전에는 자유 형식 맵으로 받아 필수 키가 없으면 NPE, 타입이 틀리면 ClassCastException 으로
 * <b>500</b> 이 났다. 이제 형태는 이 record 가 정하고, 값 검증은 {@code WorkloadService} 가 한다
 * (검증 애너테이션은 이 프로젝트에서 400 이 확실하지 않아 서비스에서 {@code BusinessException} 을 던진다 —
 * 스케줄 API 와 같은 방식).</p>
 *
 * <p>숫자는 boxed 타입이다 — "보내지 않음"(null)을 "0 을 보냄"과 구분해 필수 누락을 정확히 잡는다.
 * 열거형은 문자열로 받는다 — 기존 파서가 대소문자를 무시하므로({@code "vm"} 허용) 그 관대함을 유지한다.
 * 모르는 키는 예전처럼 무시된다.</p>
 */
public record WorkloadRequest(
        String name,
        String description,
        Integer requiredCpu,
        Integer requiredMemoryMb,
        Integer requiredGpu,
        Integer requiredDiskGb,
        String resourceType,
        String dataMdcId,
        String preferredMdcId,
        Double maxPowerBudgetWatts,
        Double maxTemperatureCelsius,
        String priority
) {}
