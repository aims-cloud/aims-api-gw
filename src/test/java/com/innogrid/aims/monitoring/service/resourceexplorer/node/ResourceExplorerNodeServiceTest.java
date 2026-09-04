package com.innogrid.aims.monitoring.service.resourceexplorer.node;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import com.innogrid.aims.common.dto.PageResponse;
import com.innogrid.aims.common.exception.BusinessException;
import com.innogrid.aims.monitoring.dto.resourceexplorer.node.NodeDailyUsageResponse;
import com.innogrid.aims.monitoring.dto.resourceexplorer.node.NodeDetailMetric;
import com.innogrid.aims.monitoring.dto.resourceexplorer.node.NodeInfoResponse;
import com.innogrid.aims.monitoring.dto.resourceexplorer.node.NodeMetricTrendResponse;
import com.innogrid.aims.monitoring.dto.resourceexplorer.node.NodeSummaryResponse;
import com.innogrid.aims.monitoring.dto.resourceexplorer.node.NodeVmListItemResponse;
import com.innogrid.aims.monitoring.dto.resourceexplorer.mdc.NodeMetricInterval;
import com.innogrid.aims.monitoring.dto.resourceexplorer.mdc.NodeStatus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.server.ResponseStatusException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
// TODO:: 자원 관리 데이터 연동 시 MockDataProvider 기반 검증을 실제 원장 대역 기반 검증으로 교체
class ResourceExplorerNodeServiceTest {

    @Autowired
    ResourceExplorerNodeService service;

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    private static void loginWithRoles(String... roles) {
        var authorities = java.util.Arrays.stream(roles)
                .map(r -> new SimpleGrantedAuthority("ROLE_" + r))
                .toList();
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("tester", null, authorities));
    }

    @Test
    void 주요_지표_카드는_사용률과_Free_Total_용량을_담는다() {
        loginWithRoles("ADMIN");
        NodeSummaryResponse summary = service.getNodeSummary("node-01");

        assertThat(summary.lastUpdatedAt()).isNotNull();
        assertThat(summary.cpu().utilizationPercent()).isEqualTo(32.5);
        assertThat(summary.cpu().coreCount()).isEqualTo(40);                 // 탑재 코어 수 보조 표기
        assertThat(summary.memory().utilizationPercent()).isEqualTo(40.3);   // 51.6 / 128.0
        assertThat(summary.memory().freeGb()).isEqualTo(76.4);               // 128.0 - 51.6
        assertThat(summary.fileSystem().totalGb()).isEqualTo(1024.0);
        assertThat(summary.networkTraffic().receiveBytesPerSec()).isEqualTo(1_048_576.0);
        assertThat(summary.gpu().utilizationPercent()).isEqualTo(88.5);
        assertThat(summary.gpu().gpuCount()).isEqualTo(2);
        assertThat(summary.vram().utilizationPercent()).isEqualTo(78.0);     // 62.4 / 80.0
    }

    @Test
    void 미수집_노드는_카드가_null_이다() {
        loginWithRoles("USER");
        NodeSummaryResponse summary = service.getNodeSummary("node-03");

        assertThat(summary.cpu()).isNull();
        assertThat(summary.memory()).isNull();
        assertThat(summary.networkTraffic()).isNull();
        assertThat(summary.gpu()).isNull();
        assertThat(summary.vram()).isNull();
    }

    @Test
    void 토큰_스코프에_없는_노드는_404다() {
        loginWithRoles("ADMIN");
        assertThatThrownBy(() -> service.getNodeSummary("node-02"))          // USER 역할의 노드
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> service.getNodeInfo("node-99"))            // 없는 노드
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void 요약_차트는_기간_선택에_맞는_구간과_버킷으로_시계열을_구성한다() {
        loginWithRoles("ADMIN");

        // LAST_10M — 지금 기준 10분 구간, 30초 버킷 → 21포인트
        NodeMetricTrendResponse cpu = service.getNodeMetricTrend(
                "node-01", NodeDetailMetric.CPU, NodeMetricInterval.LAST_10M).get(0);
        assertThat(cpu.unit()).isEqualTo("%");
        assertThat(cpu.to() - cpu.from()).isEqualTo(600);
        assertThat(cpu.step()).isEqualTo("30s");
        assertThat(cpu.series()).hasSize(1);
        assertThat(cpu.series().get(0).dataPoints()).hasSize(21);
        assertThat(cpu.series().get(0).dataPoints().get(0).timestamp()).isEqualTo(cpu.from());

        // 기간을 바꾸면 구간과 버킷 간격이 함께 바뀐다 (LAST_30D — 1일 버킷)
        NodeMetricTrendResponse monthly = service.getNodeMetricTrend(
                "node-01", NodeDetailMetric.CPU, NodeMetricInterval.LAST_30D).get(0);
        assertThat(monthly.to() - monthly.from()).isEqualTo(30L * 24 * 3600);
        assertThat(monthly.step()).isEqualTo("1d");
        assertThat(monthly.series().get(0).dataPoints()).hasSize(31);

        // metric 미지정 시 전체 지표(6종)를 배열로 반환
        List<NodeMetricTrendResponse> all = service.getNodeMetricTrend(
                "node-01", null, NodeMetricInterval.LAST_10M);
        assertThat(all).extracting(NodeMetricTrendResponse::metric)
                .containsExactly(NodeDetailMetric.values());

        // NETWORK 는 수신/송신 2개 시리즈
        NodeMetricTrendResponse network = service.getNodeMetricTrend(
                "node-01", NodeDetailMetric.NETWORK, NodeMetricInterval.LAST_10M).get(0);
        assertThat(network.series()).extracting(NodeMetricTrendResponse.Series::name)
                .containsExactly("receive", "transmit");

        // 미수집 노드는 빈 시리즈
        loginWithRoles("USER");
        NodeMetricTrendResponse missing = service.getNodeMetricTrend(
                "node-03", NodeDetailMetric.CPU, NodeMetricInterval.LAST_10M).get(0);
        assertThat(missing.series()).isEmpty();
    }

    @Test
    void 노드_정보는_스펙과_가상머신_수를_담는다() {
        loginWithRoles("ADMIN");
        NodeInfoResponse info = service.getNodeInfo("node-01");

        assertThat(info.name()).isEqualTo("node 01");
        assertThat(info.ip()).isEqualTo("123.123.12.1");
        assertThat(info.uptimeDays()).isEqualTo(128);
        assertThat(info.type()).isEqualTo("Hypervisor");
        assertThat(info.vmCount()).isEqualTo(1);                             // vm-01
        assertThat(info.cpuAllocatedCores()).isEqualTo(40);
        assertThat(info.memoryAllocatedGb()).isEqualTo(128.0);
        assertThat(info.storage()).isEqualTo("Ceph 10TB");
        assertThat(info.provider()).isEqualTo("OPENSTACK");
        assertThat(info.platformType()).isEqualTo("IaaS");
    }

    @Test
    void 가상머신_탭은_이_노드의_VM만_필터_정렬해_반환한다() {
        loginWithRoles("USER");
        PageResponse<NodeVmListItemResponse> vms =
                service.getNodeVms("node-02", null, null, 1, 10, "cpuUtilization,desc");

        assertThat(vms.totalElements()).isEqualTo(2);                        // vm-02, vm-03
        assertThat(vms.items()).extracting(NodeVmListItemResponse::vmId)
                .containsExactly("vm-02", "vm-03");                          // CPU 88.9 > 5.1
        assertThat(vms.items().get(0).alias()).isEqualTo("추론 VM");
        assertThat(vms.items().get(0).ipAddresses()).containsExactly("10.0.0.12", "172.20.1.12");
        assertThat(vms.items().get(0).diskUtilization()).isEqualTo(76.2);
        assertThat(vms.items().get(1).alias()).isNull();                     // vm-03 은 별칭 없음

        assertThat(service.getNodeVms("node-02", NodeStatus.PENDING, null, 1, 10, "name,asc")
                .totalElements()).isEqualTo(1);
        assertThat(service.getNodeVms("node-02", null, "vm 03", 1, 10, "name,asc")
                .totalElements()).isEqualTo(1);
        assertThat(service.getNodeVms("node-02", null, "추론", 1, 10, "name,asc")
                .totalElements()).isEqualTo(1);                              // 별칭으로도 검색된다
        assertThat(service.getNodeVms("node-02", null, null, 1, 10, "diskUtilization,desc")
                .items()).extracting(NodeVmListItemResponse::vmId)
                .containsExactly("vm-02", "vm-03");                          // Disk 76.2 > 3.9
    }

    @Test
    void 자원_사용_현황은_일자별_일간_최대치를_반환한다() {
        loginWithRoles("ADMIN");
        PageResponse<NodeDailyUsageResponse> usage =
                service.getNodeUsage("node-01", NodeMetricInterval.LAST_30D, 1, 10, "date,desc");

        assertThat(usage.totalElements()).isEqualTo(2);               // 오늘/어제
        NodeDailyUsageResponse today = usage.items().get(0);
        assertThat(today.date()).isEqualTo(LocalDate.now());
        assertThat(today.maxCpuUtilization()).isEqualTo(37.4);        // 32.5 × 1.15
        assertThat(today.maxMemoryUtilization()).isEqualTo(44.3);     // 40.3 × 1.1
        assertThat(today.maxFileSystemUtilization()).isEqualTo(52.5); // 50.0 × 1.05
        assertThat(today.maxVmCount()).isEqualTo(1);                  // vm-01
        assertThat(usage.items().get(1).maxVmCount()).isNull();       // 어제는 "-" (미수집)

        // 짧은 기간을 고르면 기간에 걸친 일자만 남는다 — 24시간 미만은 오늘 행만
        assertThat(service.getNodeUsage("node-01", NodeMetricInterval.LAST_10M, 1, 10, "date,desc")
                .totalElements()).isEqualTo(1);

        // 미수집 노드는 행은 있고 값이 전부 null("-")
        loginWithRoles("USER");
        NodeDailyUsageResponse missing = service.getNodeUsage(
                "node-03", NodeMetricInterval.LAST_30D, 1, 10, "date,desc").items().get(0);
        assertThat(missing.maxCpuUtilization()).isNull();
        assertThat(missing.maxMemoryUtilization()).isNull();
        assertThat(missing.maxVmCount()).isNull();
    }
}
