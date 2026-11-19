package com.innogrid.aims.monitoring.service.resourceexplorer.tree;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import com.innogrid.aims.common.util.MetricFormat;
import com.innogrid.aims.monitoring.dto.resource.MetricTrendResponse.DataPoint;
import com.innogrid.aims.monitoring.dto.resourceexplorer.mdc.NodeMetricInterval;
import com.innogrid.aims.monitoring.dto.resourceexplorer.node.system.SystemInfoResponse;
import com.innogrid.aims.monitoring.dto.resourceexplorer.node.system.SystemMetric;
import com.innogrid.aims.monitoring.dto.resourceexplorer.node.system.SystemMetricTrendResponse;
import com.innogrid.aims.monitoring.dto.resourceexplorer.node.system.SystemMetricTrendResponse.Series;
import com.innogrid.aims.monitoring.dto.resourceexplorer.node.system.SystemSummaryResponse;
import com.innogrid.aims.monitoring.dto.resourceexplorer.tree.MonitoringStatus;
import com.innogrid.aims.monitoring.service.resourceexplorer.tree.HostSystemMetricsQueryService.SystemInfo;
import com.innogrid.aims.monitoring.service.resourceexplorer.tree.HostSystemMetricsQueryService.SystemSummary;

/**
 * System 카테고리 패널 응답 조립 — 노드·VM 패널 서비스가 공유하는 순수 계산 부분.
 *
 * Thanos 표본({@link SystemSummary}·{@link SystemInfo})을 KPI 카드(부팅 시각 → 가동 시간)·시스템 정보 표·시계열 응답으로
 * 바꾸는 규칙만 담고, 자원 식별(노드 assets.id / VM nsId·infraId·vmId)과 스코프 검증은 각 패널 서비스가 맡는다.
 */
public final class SystemPanelAssembler {

    private SystemPanelAssembler() {
    }

    /** 시계열 조회 — from/to/step 을 받아 DataPoint 목록을 돌려준다(미수집은 빈 목록) */
    @FunctionalInterface
    public interface TrendQuery {
        List<DataPoint> points(Instant from, Instant to, String step);
    }

    /** 개요 탭 KPI 카드 — 가동 시간(초, 부팅 시각으로 계산), 부하 1/5/15, 코어 수, 컨텍스트 스위치·인터럽트 /s. 미수집은 null */
    public static SystemSummaryResponse summary(SystemSummary summary) {
        return new SystemSummaryResponse(
                uptimeSeconds(summary.bootTimeSeconds()),
                summary.load1(), summary.load5(), summary.load15(),
                summary.coreCount(),
                summary.contextSwitchesPerSec(), summary.interruptsPerSec());
    }

    /** 개요 탭 시스템 정보 표 — 표본이 있으면 COLLECTING 으로 라벨을 옮기고, 없으면 UNAVAILABLE 빈 표 */
    public static SystemInfoResponse info(Optional<SystemInfo> info) {
        if (info.isEmpty()) {
            return emptyInfo(MonitoringStatus.UNAVAILABLE);
        }
        SystemInfo i = info.get();
        return new SystemInfoResponse(i.hostname(), i.osName(), i.osVersion(), i.kernelRelease(), i.architecture(),
                i.bootTimeSeconds() == null ? null : MetricFormat.toOffsetDateTime(Instant.ofEpochSecond(i.bootTimeSeconds())),
                MonitoringStatus.COLLECTING,
                MetricFormat.toOffsetDateTime(i.collectedAt()));
    }

    /** 값 없는 시스템 정보 표 — exporter 미등록(NOT_CONFIGURED) 또는 표본 없음(UNAVAILABLE) */
    public static SystemInfoResponse emptyInfo(MonitoringStatus status) {
        return new SystemInfoResponse(null, null, null, null, null, null, status, null);
    }

    /** 모니터링 차트 — 조회 구간은 지금 기준 interval 기간, 버킷 간격은 기간별 defaultStep. 미수집이면 빈 시리즈 */
    public static SystemMetricTrendResponse trend(SystemMetric metric, NodeMetricInterval interval, TrendQuery query) {
        Instant to = Instant.now();
        Instant from = to.minus(interval.window());
        String step = interval.defaultStep();
        List<DataPoint> points = query.points(from, to, step);
        return new SystemMetricTrendResponse(metric, metric.unit(), from.getEpochSecond(), to.getEpochSecond(), step,
                points.isEmpty() ? List.of() : List.of(new Series("system", points)));
    }

    /** 가동 시간(초) — 부팅 시각이 없으면 null, 시계 오차로 음수가 되면 0 */
    public static Long uptimeSeconds(Long bootTimeSeconds) {
        if (bootTimeSeconds == null) {
            return null;
        }
        return Math.max(0L, Instant.now().getEpochSecond() - bootTimeSeconds);
    }
}
