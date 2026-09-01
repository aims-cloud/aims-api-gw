package com.innogrid.aims.monitoring.service.resourceexplorer.node;

import java.util.List;

import com.innogrid.aims.common.dto.PageResponse;
import com.innogrid.aims.common.exception.BusinessException;
import com.innogrid.aims.monitoring.dto.resourceexplorer.nodegroup.NodeGroupSummaryResponse;
import com.innogrid.aims.monitoring.dto.resourceexplorer.nodegroup.NodeListItemResponse;
import com.innogrid.aims.monitoring.dto.resourceexplorer.nodegroup.NodeMetric;
import com.innogrid.aims.monitoring.dto.resourceexplorer.nodegroup.NodeMetricInterval;
import com.innogrid.aims.monitoring.dto.resourceexplorer.nodegroup.NodeSearchField;
import com.innogrid.aims.monitoring.dto.resourceexplorer.nodegroup.NodeStatus;
import com.innogrid.aims.monitoring.dto.resourceexplorer.nodegroup.NodeTopResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
// TODO:: 자원 관리 데이터 연동 시 MockDataProvider 기반 검증을 실제 원장 대역 기반 검증으로 교체
class ResourceExplorerNodeGroupServiceTest {

    @Autowired
    ResourceExplorerNodeGroupService service;

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
    void 개요_KPI는_노드그룹의_상태별_카운트를_집계한다() {
        loginWithRoles("ADMIN");
        NodeGroupSummaryResponse summary = service.getNodeGroupSummary("ADMIN", "Openstack-GPU");

        assertThat(summary.totalCount()).isEqualTo(1);                // node-01 (RUNNING)
        assertThat(summary.runningCount()).isEqualTo(1);
        assertThat(summary.pendingCount()).isZero();                  // Mock 노드에 PENDING 없음
        assertThat(summary.stopCount()).isZero();
        assertThat(summary.errorCount()).isZero();
    }

    @Test
    void 조회는_토큰의_역할_스코프로_제한된다() {
        loginWithRoles("ADMIN");
        // 토큰에 없는 역할을 지정하면 빈 결과
        assertThat(service.getNodeGroupSummary("USER", null).totalCount()).isZero();
        assertThat(service.getNodeGroupNodes("USER", null, null, NodeSearchField.NAME, null, 1, 10, "name,asc")
                .totalElements()).isZero();

        // 필터를 안 주면 토큰 역할 스코프 전체
        loginWithRoles("ADMIN", "USER");
        assertThat(service.getNodeGroupSummary(null, null).totalCount()).isEqualTo(3);

        SecurityContextHolder.clearContext();                         // 미인증 → 빈 결과
        assertThat(service.getNodeGroupSummary(null, null).totalCount()).isZero();
    }

    @Test
    void Top_N은_요청_지표_내림차순으로_보조_지표를_동봉한다() {
        loginWithRoles("ADMIN", "USER");
        List<NodeTopResponse> top = service.getNodeGroupTop(
                null, null, NodeMetric.CPU, NodeMetricInterval.LAST_10M, 5);

        // 요청 지표 미수집 노드(node-03)는 제외 — 남는 슬롯은 화면이 "미수집"으로 채운다
        assertThat(top).hasSize(2);
        assertThat(top.get(0).nodeId()).isEqualTo("node-02");         // CPU 78.1 > 32.5
        assertThat(top.get(0).value()).isEqualTo(78.1);
        assertThat(top.get(0).memoryUtilization()).isEqualTo(75.3);   // 96.4 / 128.0 — 보조 지표 동봉
        assertThat(top.get(1).nodeId()).isEqualTo("node-01");

        List<NodeTopResponse> limited = service.getNodeGroupTop(
                null, null, NodeMetric.MEMORY, NodeMetricInterval.LAST_10M, 1);
        assertThat(limited).hasSize(1);
        assertThat(limited.get(0).value()).isEqualTo(75.3);           // MEMORY 토글 시 value 교체

        // File system 토글은 Free/Total 용량을 함께 표시한다
        List<NodeTopResponse> fs = service.getNodeGroupTop(
                null, null, NodeMetric.FILE_SYSTEM, NodeMetricInterval.LAST_10M, 5);
        assertThat(fs.get(0).fileSystemFreeGb()).isEqualTo(133.5);    // node-02: 1024.0 - 890.5
        assertThat(fs.get(0).fileSystemTotalGb()).isEqualTo(1024.0);
    }

    @Test
    void Top_N은_집계_기간에_따라_평균값이_달라진다() {
        loginWithRoles("ADMIN", "USER");
        List<NodeTopResponse> daily = service.getNodeGroupTop(
                null, null, NodeMetric.CPU, NodeMetricInterval.LAST_24H, 5);

        assertThat(daily.get(0).value()).isEqualTo(54.7);             // 78.1 × 0.7 (Mock 감쇠)
        // 기간과 무관한 용량 값은 그대로
        assertThat(daily.get(0).fileSystemTotalGb()).isEqualTo(1024.0);
    }

    @Test
    void 노드_목록은_사용률과_복수_IP를_내려주고_미수집은_null_이다() {
        loginWithRoles("ADMIN");
        PageResponse<NodeListItemResponse> pageResponse = service.getNodeGroupNodes(
                "ADMIN", "Openstack-GPU", null, NodeSearchField.NAME, null, 1, 10, "name,asc");

        assertThat(pageResponse.totalElements()).isEqualTo(1);
        NodeListItemResponse row = pageResponse.items().get(0);
        assertThat(row.nodeId()).isEqualTo("node-01");
        assertThat(row.alias()).isEqualTo("GPU 학습 노드");
        assertThat(row.status()).isEqualTo(NodeStatus.RUNNING);
        assertThat(row.ipAddresses()).containsExactly("123.123.12.1");
        assertThat(row.cpuUtilization()).isEqualTo(32.5);
        assertThat(row.memoryUtilization()).isEqualTo(40.3);          // 51.6 / 128.0
        assertThat(row.fileSystemUtilization()).isEqualTo(50.0);      // 512.0 / 1024.0

        // 미수집 노드(node-03) — 별칭 없음, IP 2개, 사용률 전부 null("-")
        loginWithRoles("USER");
        NodeListItemResponse missing = service.getNodeGroupNodes(
                        "USER", null, null, NodeSearchField.NAME, "node 03", 1, 10, "name,asc")
                .items().get(0);
        assertThat(missing.alias()).isNull();
        assertThat(missing.ipAddresses()).containsExactly("123.123.180.82", "172.20.0.232");
        assertThat(missing.cpuUtilization()).isNull();
        assertThat(missing.memoryUtilization()).isNull();
        assertThat(missing.fileSystemUtilization()).isNull();
    }

    @Test
    void 노드_목록은_상태와_검색_필드로_필터하고_정렬한다() {
        loginWithRoles("ADMIN", "USER");
        assertThat(service.getNodeGroupNodes(null, null, NodeStatus.ERROR, NodeSearchField.NAME, null, 1, 10, "name,asc")
                .totalElements()).isEqualTo(2);                       // node-02, node-03
        // searchField 가 지정한 필드에서만 검색된다
        assertThat(service.getNodeGroupNodes(null, null, null, NodeSearchField.ALIAS, "추론", 1, 10, "name,asc")
                .totalElements()).isEqualTo(1);
        assertThat(service.getNodeGroupNodes(null, null, null, NodeSearchField.NAME, "추론", 1, 10, "name,asc")
                .totalElements()).isZero();                           // 이름에는 "추론"이 없다
        assertThat(service.getNodeGroupNodes(null, null, null, NodeSearchField.IP, "172.20.0.44", 1, 10, "name,asc")
                .totalElements()).isEqualTo(1);                       // 두 번째 IP 로도 검색된다
        assertThat(service.getNodeGroupNodes(null, null, null, NodeSearchField.NAME, "없는노드", 1, 10, "name,asc")
                .totalElements()).isZero();
        // cpuUtilization 내림차순 정렬 — 미수집(null)은 방향과 무관하게 마지막
        assertThat(service.getNodeGroupNodes(null, null, null, NodeSearchField.NAME, null, 1, 10, "cpuUtilization,desc")
                .items()).extracting(NodeListItemResponse::nodeId)
                .containsExactly("node-02", "node-01", "node-03");
        assertThat(service.getNodeGroupNodes(null, null, null, NodeSearchField.NAME, null, 1, 10, "cpuUtilization,asc")
                .items()).extracting(NodeListItemResponse::nodeId)
                .containsExactly("node-01", "node-02", "node-03");
    }

    @Test
    void 지원하지_않는_정렬_필드는_거부한다() {
        loginWithRoles("ADMIN");
        assertThatThrownBy(() -> service.getNodeGroupNodes(
                null, null, null, NodeSearchField.NAME, null, 1, 10, "unknownField,asc"))
                .isInstanceOf(BusinessException.class);
    }
}
