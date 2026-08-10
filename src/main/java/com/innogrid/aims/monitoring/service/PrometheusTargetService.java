package com.innogrid.aims.monitoring.service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.innogrid.aims.monitoring.entity.MonitoringTarget;
import com.innogrid.aims.monitoring.repository.MonitoringTargetRepository;

/**
 * Prometheus file-based service discovery 파일을 생성한다.
 *
 * AIMS에서 수집 대상을 등록/삭제하면 JSON 파일이 자동 갱신되고,
 * Prometheus가 이를 주기적으로 읽어 scrape 대상을 갱신한다.
 *
 * prometheus.yml 설정:
 *   - job_name: "aims-targets"
 *     file_sd_configs:
 *       - files: ["/etc/prometheus/targets/*.json"]
 *         refresh_interval: 30s
 */
@Service
public class PrometheusTargetService {

    private static final Logger log = LoggerFactory.getLogger(PrometheusTargetService.class);

    private final MonitoringTargetRepository targetRepository;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final String targetDir;

    public PrometheusTargetService(MonitoringTargetRepository targetRepository,
                                   @Value("${prometheus.file-sd.target-dir:#{null}}") String targetDir) {
        this.targetRepository = targetRepository;
        this.targetDir = targetDir;
    }

    /**
     * 1분마다 DB의 수집 대상을 Prometheus file_sd 형식의 JSON으로 내보낸다.
     * targetDir이 설정되지 않으면 실행하지 않는다.
     */
    @Scheduled(fixedRate = 60_000)
    public void exportTargets() {
        if (targetDir == null) return;

        try {
            Path dir = Path.of(targetDir);
            if (!Files.exists(dir)) {
                Files.createDirectories(dir);
            }

            for (MonitoringTarget.ExporterType type : MonitoringTarget.ExporterType.values()) {
                List<MonitoringTarget> targets = targetRepository.findByExporterType(type);
                List<Map<String, Object>> sdConfig = toFileSdFormat(targets, type);
                String filename = type.name().toLowerCase() + ".json";
                Path filePath = dir.resolve(filename);
                objectMapper.writerWithDefaultPrettyPrinter().writeValue(filePath.toFile(), sdConfig);
            }
            log.debug("Prometheus 타겟 파일 갱신 완료: {}", targetDir);
        } catch (IOException e) {
            log.warn("Prometheus 타겟 파일 갱신 실패: {}", e.getMessage());
        }
    }

    /**
     * 현재 DB에 등록된 모든 타겟을 Prometheus file_sd JSON 형식으로 반환한다.
     * 파일 쓰기 없이 API로 직접 조회할 때 사용.
     */
    public List<Map<String, Object>> getTargetsAsFileSd() {
        List<Map<String, Object>> result = new ArrayList<>();
        for (MonitoringTarget.ExporterType type : MonitoringTarget.ExporterType.values()) {
            List<MonitoringTarget> targets = targetRepository.findByExporterType(type);
            result.addAll(toFileSdFormat(targets, type));
        }
        return result;
    }

    private List<Map<String, Object>> toFileSdFormat(List<MonitoringTarget> targets,
                                                      MonitoringTarget.ExporterType type) {
        // MDC별로 그룹핑
        Map<String, List<MonitoringTarget>> byMdc = targets.stream()
                .collect(Collectors.groupingBy(MonitoringTarget::getMdcId));

        List<Map<String, Object>> sdEntries = new ArrayList<>();
        for (Map.Entry<String, List<MonitoringTarget>> entry : byMdc.entrySet()) {
            List<String> addrs = entry.getValue().stream()
                    .map(MonitoringTarget::getScrapeEndpoint)
                    .toList();

            Map<String, Object> sdEntry = new LinkedHashMap<>();
            sdEntry.put("targets", addrs);
            sdEntry.put("labels", Map.of(
                    "mdc_id", entry.getKey(),
                    "exporter_type", type.name().toLowerCase(),
                    "job", jobNameFor(type)
            ));
            sdEntries.add(sdEntry);
        }
        return sdEntries;
    }

    private String jobNameFor(MonitoringTarget.ExporterType type) {
        return switch (type) {
            case NODE_EXPORTER -> "mdc-nodes";
            case DCGM_EXPORTER -> "gpu-metrics";
            case IPMI_EXPORTER -> "ipmi-metrics";
            case SNMP_EXPORTER -> "snmp-metrics";
        };
    }
}
