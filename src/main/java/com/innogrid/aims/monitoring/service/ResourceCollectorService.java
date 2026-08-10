package com.innogrid.aims.monitoring.service;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.innogrid.aims.monitoring.VictoriaMetricsClient;
import com.innogrid.aims.monitoring.entity.MonitoringTarget;
import com.innogrid.aims.monitoring.entity.MonitoringTarget.TargetStatus;
import com.innogrid.aims.monitoring.repository.MonitoringTargetRepository;

/**
 * 자원 수집 대상(에이전트) 관리 서비스.
 * - 수집 대상 노드 등록/삭제
 * - Prometheus의 up 메트릭으로 에이전트 상태를 주기적으로 갱신
 */
@Service
public class ResourceCollectorService {

    private static final Logger log = LoggerFactory.getLogger(ResourceCollectorService.class);

    private final MonitoringTargetRepository targetRepository;
    private final VictoriaMetricsClient vmClient;

    public ResourceCollectorService(MonitoringTargetRepository targetRepository,
                                    VictoriaMetricsClient vmClient) {
        this.targetRepository = targetRepository;
        this.vmClient = vmClient;
    }

    public List<MonitoringTarget> listTargets(String mdcId) {
        if (mdcId != null) {
            return targetRepository.findByMdcId(mdcId);
        }
        return targetRepository.findAll();
    }

    public MonitoringTarget getTarget(Long id) {
        return targetRepository.findById(id).orElse(null);
    }

    @Transactional
    public MonitoringTarget registerTarget(String mdcId, String hostname, String address,
                                           int port, MonitoringTarget.ExporterType exporterType) {
        MonitoringTarget target = new MonitoringTarget(mdcId, hostname, address, port, exporterType);
        return targetRepository.save(target);
    }

    @Transactional
    public void removeTarget(Long id) {
        targetRepository.deleteById(id);
    }

    /**
     * 5분마다 Prometheus의 up 메트릭을 조회하여 에이전트 상태를 갱신한다.
     */
    @Scheduled(fixedRate = 300_000)
    @Transactional
    public void refreshAgentStatus() {
        List<MonitoringTarget> targets = targetRepository.findAll();
        if (targets.isEmpty()) return;

        try {
            List<Map<String, Object>> upResults = vmClient.queryRangeResults(
                    "up", java.time.Instant.now().minusSeconds(60), java.time.Instant.now(), "60s");

            for (MonitoringTarget target : targets) {
                boolean found = false;
                for (Map<String, Object> result : upResults) {
                    @SuppressWarnings("unchecked")
                    Map<String, String> labels = (Map<String, String>) result.get("metric");
                    String instance = labels.getOrDefault("instance", "");

                    if (instance.equals(target.getScrapeEndpoint())) {
                        @SuppressWarnings("unchecked")
                        List<List<Object>> values = (List<List<Object>>) result.get("values");
                        if (values != null && !values.isEmpty()) {
                            List<Object> lastValue = values.get(values.size() - 1);
                            double val = Double.parseDouble(lastValue.get(1).toString());
                            target.setStatus(val == 1.0 ? TargetStatus.UP : TargetStatus.DOWN);
                            target.setLastSeenAt(OffsetDateTime.now());
                            found = true;
                        }
                        break;
                    }
                }
                if (!found) {
                    target.setStatus(TargetStatus.DOWN);
                }
            }
            targetRepository.saveAll(targets);
            log.debug("에이전트 상태 갱신 완료: {}개 대상", targets.size());
        } catch (Exception e) {
            log.warn("에이전트 상태 갱신 실패: {}", e.getMessage());
        }
    }
}
