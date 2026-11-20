package com.innogrid.aims.scheduler.dto;

/**
 * 워크로드 생성 요청.
 *
 * <p>자유 형식 맵으로 받으면 필수 키 누락이나 타입 오류가 <b>500</b>이 되므로 형태는 이 record가 정하고, 값 검증은
 * {@code WorkloadService}가 한다. 검증 애너테이션은 이 프로젝트에서 400이 확실하지 않아 서비스에서
 * {@code BusinessException}을 던진다(스케줄 API와 같은 방식).</p>
 *
 * <p>숫자는 "보내지 않음"(null)을 "0을 보냄"과 구분해 필수 누락을 정확히 잡도록 boxed 타입으로 받는다.
 * 열거형은 대소문자를 무시하는 파서의 관대함({@code "vm"} 허용)을 유지하도록 문자열로 받는다. 모르는 키는 무시된다.</p>
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
        Double requiredBandwidthMbps,
        String priority
) {}
