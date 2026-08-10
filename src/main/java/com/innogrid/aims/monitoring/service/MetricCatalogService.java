package com.innogrid.aims.monitoring.service;

import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;

import com.innogrid.aims.monitoring.entity.MonitoringTarget.ExporterType;

/**
 * 각 Exporter 유형별 수집 가능한 메트릭 카탈로그를 제공한다.
 * Frontend에서 메트릭 선택 UI를 구성할 때 사용.
 */
@Service
public class MetricCatalogService {

    public record MetricInfo(String name, String type, String unit, String description) {}

    public List<MetricInfo> getMetrics(ExporterType exporterType) {
        return switch (exporterType) {
            case NODE_EXPORTER -> NODE_METRICS;
            case DCGM_EXPORTER -> GPU_METRICS;
            case IPMI_EXPORTER -> IPMI_METRICS;
            case SNMP_EXPORTER -> SNMP_METRICS;
        };
    }

    public Map<String, List<MetricInfo>> getAllMetrics() {
        return Map.of(
                "NODE_EXPORTER", NODE_METRICS,
                "DCGM_EXPORTER", GPU_METRICS,
                "IPMI_EXPORTER", IPMI_METRICS,
                "SNMP_EXPORTER", SNMP_METRICS
        );
    }

    private static final List<MetricInfo> NODE_METRICS = List.of(
            new MetricInfo("node_cpu_seconds_total", "counter", "seconds", "CPU 모드별 누적 시간"),
            new MetricInfo("node_load1", "gauge", "", "1분 로드 평균"),
            new MetricInfo("node_load5", "gauge", "", "5분 로드 평균"),
            new MetricInfo("node_load15", "gauge", "", "15분 로드 평균"),
            new MetricInfo("node_memory_MemTotal_bytes", "gauge", "bytes", "전체 메모리"),
            new MetricInfo("node_memory_MemAvailable_bytes", "gauge", "bytes", "가용 메모리"),
            new MetricInfo("node_filesystem_size_bytes", "gauge", "bytes", "파일시스템 전체 크기"),
            new MetricInfo("node_filesystem_avail_bytes", "gauge", "bytes", "파일시스템 가용 크기"),
            new MetricInfo("node_disk_read_bytes_total", "counter", "bytes", "디스크 읽기 총량"),
            new MetricInfo("node_disk_written_bytes_total", "counter", "bytes", "디스크 쓰기 총량"),
            new MetricInfo("node_network_receive_bytes_total", "counter", "bytes", "네트워크 수신 총량"),
            new MetricInfo("node_network_transmit_bytes_total", "counter", "bytes", "네트워크 송신 총량"),
            new MetricInfo("node_procs_running", "gauge", "", "실행 중 프로세스 수"),
            new MetricInfo("node_uname_info", "info", "", "커널 버전, OS 정보")
    );

    private static final List<MetricInfo> GPU_METRICS = List.of(
            new MetricInfo("DCGM_FI_DEV_GPU_UTIL", "gauge", "%", "GPU 사용률"),
            new MetricInfo("DCGM_FI_DEV_MEM_COPY_UTIL", "gauge", "%", "GPU 메모리 대역폭 사용률"),
            new MetricInfo("DCGM_FI_DEV_FB_USED", "gauge", "MB", "GPU 프레임버퍼 사용량"),
            new MetricInfo("DCGM_FI_DEV_FB_FREE", "gauge", "MB", "GPU 프레임버퍼 여유량"),
            new MetricInfo("DCGM_FI_DEV_GPU_TEMP", "gauge", "°C", "GPU 온도"),
            new MetricInfo("DCGM_FI_DEV_POWER_USAGE", "gauge", "W", "GPU 전력 소모"),
            new MetricInfo("DCGM_FI_DEV_SM_CLOCK", "gauge", "MHz", "SM 클럭 속도")
    );

    private static final List<MetricInfo> IPMI_METRICS = List.of(
            new MetricInfo("ipmi_temperature_celsius", "gauge", "°C", "센서별 온도"),
            new MetricInfo("ipmi_fan_speed_rpm", "gauge", "RPM", "팬 속도"),
            new MetricInfo("ipmi_power_watts", "gauge", "W", "전력 소모"),
            new MetricInfo("ipmi_voltage_volts", "gauge", "V", "전압"),
            new MetricInfo("ipmi_dcmi_power_consumption_watts", "gauge", "W", "DCMI 전력 소비"),
            new MetricInfo("ipmi_chassis_power_state", "gauge", "", "서버 전원 상태 (0/1)")
    );

    private static final List<MetricInfo> SNMP_METRICS = List.of(
            new MetricInfo("swFCPortTxWords", "counter", "words", "FC 포트 송신 워드"),
            new MetricInfo("swFCPortRxWords", "counter", "words", "FC 포트 수신 워드"),
            new MetricInfo("swFCPortCRCErrors", "counter", "", "CRC 에러 수"),
            new MetricInfo("swSfpTemperature", "gauge", "°C", "SFP 온도"),
            new MetricInfo("swSfpVoltage", "gauge", "mV", "SFP 전압"),
            new MetricInfo("swSfpRxPower", "gauge", "dBm", "SFP 수신 광출력"),
            new MetricInfo("swSfpTxPower", "gauge", "dBm", "SFP 송신 광출력")
    );
}
