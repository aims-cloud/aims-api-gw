package com.innogrid.aims.bmc;

/**
 * Redfish ComputerSystem을 정규화한 하드웨어 정보.
 * XCC(Lenovo)·IDRAC(Dell) 공통 필드만 담는다. 조회 실패/미구성 시 호출 측이 null로 처리한다.
 *
 * @param endpointId   BMC 엔드포인트 식별자
 * @param manufacturer 제조사
 * @param model        모델명
 * @param serialNumber 시리얼 넘버
 * @param hostName     호스트명(설정된 경우)
 * @param powerState   전원 상태 (On/Off)
 * @param health       상태 요약 (OK/Warning/Critical)
 * @param cpuModel     CPU 모델명
 * @param cpuCount     소켓(프로세서) 수
 * @param coreCount    총 물리 코어 수
 * @param threadCount  총 논리 프로세서(스레드) 수
 * @param memoryGiB    메모리 총량(GiB)
 */
public record RedfishSystem(
        String endpointId,
        String manufacturer,
        String model,
        String serialNumber,
        String hostName,
        String powerState,
        String health,
        String cpuModel,
        Integer cpuCount,
        Integer coreCount,
        Integer threadCount,
        Integer memoryGiB
) {}
