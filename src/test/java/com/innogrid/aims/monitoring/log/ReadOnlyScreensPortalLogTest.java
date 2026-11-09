package com.innogrid.aims.monitoring.log;

import java.util.List;
import java.util.Map;

import com.innogrid.aims.common.exception.BusinessException;
import com.innogrid.aims.dashboard.service.GpuDashboardService;
import com.innogrid.aims.infra.service.RackService;
import com.innogrid.aims.infra.service.VirtualAssetService;
import com.innogrid.aims.monitoring.dto.log.PortalLogResult;
import com.innogrid.aims.monitoring.dto.log.PortalLogType;
import com.innogrid.aims.monitoring.entity.PortalLogEntry;
import com.innogrid.aims.monitoring.repository.PortalLogRepository;
import com.innogrid.aims.openstack.dto.TopologyDtos.DeployZone;
import com.innogrid.aims.openstack.service.OpenStackTopologyDetailService;
import com.innogrid.aims.openstack.service.OpenStackTopologyService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpStatus;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 조회 전용 화면(통합 토폴로지·랙실장도·가상화 자산·GPU 대시보드 진입 조회·사용자/역할 목록)의 {@code @PortalLog(ACCESS)} 적용 확인 —
 * 화면명·내용 문구·ref 가 설계대로 남는지, 자동 갱신 대상인 GPU 대시보드 본문은 남기지 않는지, 실패가 FAILURE 로 남는지 MockMvc 로 본다.
 * 외부 연동(OpenStack·IPMI·Thanos) 서비스는 대역. 로그는 REQUIRES_NEW 로 커밋되므로 테스트 트랜잭션을 쓰지 않고 직접 지운다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@WithMockUser(username = "admin@innogrid.com")
class ReadOnlyScreensPortalLogTest {

    @Autowired
    MockMvc mockMvc;
    @Autowired
    PortalLogRepository repository;
    @MockitoBean
    OpenStackTopologyService topologyService;
    @MockitoBean
    OpenStackTopologyDetailService topologyDetailService;
    @MockitoBean
    RackService rackService;
    @MockitoBean
    VirtualAssetService virtualAssetService;
    @MockitoBean
    GpuDashboardService gpuDashboardService;

    @BeforeEach
    @AfterEach
    void clean() {
        repository.deleteAll();
    }

    @Test
    void 토폴로지_배포영역과_트리_조회는_통합_토폴로지_화면의_ACCESS로_남는다() throws Exception {
        when(topologyService.listDeployZones()).thenReturn(List.of(new DeployZone("kr-1", "openstack", true)));
        when(topologyService.getTopology()).thenReturn(null);

        mockMvc.perform(get("/api/v1/topology/deploy-zones")).andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/topology").param("deployZone", "kr-1")).andExpect(status().isOk());

        List<PortalLogEntry> logs = repository.findAll();
        assertThat(logs).hasSize(2);
        assertThat(logs).allSatisfy(log -> {
            assertThat(log.getType()).isEqualTo(PortalLogType.ACCESS);
            assertThat(log.getScreen()).isEqualTo("통합 토폴로지");
            assertThat(log.getAction()).isEqualTo("조회");
            assertThat(log.getUserId()).isEqualTo("admin@innogrid.com");
        });
        assertThat(logs).extracting(PortalLogEntry::getContent)
                .containsExactlyInAnyOrder("배포영역 목록 조회", "통합 토폴로지 트리 조회 (배포영역: kr-1)");
    }

    @Test
    void 토폴로지_노드_상세는_OpenStack_종류별_ref를_건다() throws Exception {
        when(topologyDetailService.getServerDetail("srv-9")).thenReturn(null);

        mockMvc.perform(get("/api/v1/topology/servers/srv-9")).andExpect(status().isOk());

        List<PortalLogEntry> logs = repository.findAll();
        assertThat(logs).hasSize(1);
        assertThat(logs.get(0).getContent()).isEqualTo("가상서버 상세 조회: srv-9");
        assertThat(logs.get(0).getRefType()).isEqualTo("OPENSTACK_SERVER");
        assertThat(logs.get(0).getRefId()).isEqualTo("srv-9");
    }

    @Test
    void 랙_목록은_필터를_남기고_실장_현황은_랙을_ref로_건다() throws Exception {
        when(rackService.search(any(), any(), any(), any(), any(), any(), any())).thenReturn(List.of());
        when(rackService.getLayout(4L)).thenReturn(Map.of());

        mockMvc.perform(get("/api/v1/racks").param("mdcId", "mdc-01")).andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/racks/4/layout")).andExpect(status().isOk());

        List<PortalLogEntry> logs = repository.findAll();
        assertThat(logs).hasSize(2);
        assertThat(logs).allSatisfy(log -> assertThat(log.getScreen()).isEqualTo("랙실장도"));
        assertThat(logs).extracting(PortalLogEntry::getContent)
                .containsExactlyInAnyOrder("랙 목록 조회 (MDC: mdc-01, 분류: 전체, 위치: 전체)", "랙 실장 현황 조회: 랙 id 4");
        assertThat(logs).filteredOn(log -> log.getRefType() != null).singleElement()
                .satisfies(log -> {
                    assertThat(log.getRefType()).isEqualTo("RACK");
                    assertThat(log.getRefId()).isEqualTo("4");
                });
    }

    @Test
    void 가상_호스트_조회는_가상화_자산_화면으로_남고_물리_자산_조회는_남기지_않는다() throws Exception {
        when(virtualAssetService.listHosts(any())).thenReturn(List.of());

        mockMvc.perform(get("/api/v1/assets/virtual/hosts")).andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/assets")).andExpect(status().isOk());                 // 물리 자산 목록 — 프론트 미연결, 미부착

        List<PortalLogEntry> logs = repository.findAll();
        assertThat(logs).hasSize(1);
        assertThat(logs.get(0).getScreen()).isEqualTo("가상화 자산");
        assertThat(logs.get(0).getContent()).isEqualTo("가상 호스트 목록 조회 (공급자: 전체)");
    }

    @Test
    void GPU_대시보드는_진입_조회만_남기고_자동_갱신_본문은_남기지_않는다() throws Exception {
        when(gpuDashboardService.getFilterOptions(any())).thenReturn(null);
        when(gpuDashboardService.getThresholds()).thenReturn(null);
        when(gpuDashboardService.getDashboard(any(), any(), any())).thenReturn(null);

        mockMvc.perform(get("/api/v1/dashboards/gpu/filter-options")).andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/dashboards/gpu/thresholds")).andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/dashboards/gpu")).andExpect(status().isOk());       // 60초 자동 갱신 대상 — 미부착

        List<PortalLogEntry> logs = repository.findAll();
        assertThat(logs).hasSize(2);
        assertThat(logs).allSatisfy(log -> assertThat(log.getScreen()).isEqualTo("GPU 대시보드"));
        assertThat(logs).extracting(PortalLogEntry::getContent)
                .containsExactlyInAnyOrder("GPU 대시보드 필터 옵션 조회 (역할: 전체)", "GPU 임계값 조회");
    }

    @Test
    void 사용자_역할_목록_조회는_실제_저장소로_ACCESS가_남는다() throws Exception {
        mockMvc.perform(get("/api/v1/users")).andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/roles")).andExpect(status().isOk());

        List<PortalLogEntry> logs = repository.findAll();
        assertThat(logs).hasSize(2);
        assertThat(logs).extracting(PortalLogEntry::getScreen).containsExactlyInAnyOrder("사용자 관리", "역할/권한");
        assertThat(logs).extracting(PortalLogEntry::getContent).containsExactlyInAnyOrder("사용자 목록 조회", "역할 목록 조회");
    }

    @Test
    void 서비스_예외는_FAILURE와_메시지로_남는다() throws Exception {
        when(virtualAssetService.listHostVms(anyString()))
                .thenThrow(new BusinessException(HttpStatus.NOT_FOUND, "호스트를 찾을 수 없습니다: h-x"));

        mockMvc.perform(get("/api/v1/assets/virtual/hosts/h-x/vms")).andExpect(status().isNotFound());

        List<PortalLogEntry> logs = repository.findAll();
        assertThat(logs).hasSize(1);
        assertThat(logs.get(0).getResult()).isEqualTo(PortalLogResult.FAILURE);
        assertThat(logs.get(0).getDescription()).isEqualTo("호스트를 찾을 수 없습니다: h-x");
        assertThat(logs.get(0).getContent()).isEqualTo("가상 호스트 VM 목록 조회: 호스트 h-x");
    }
}
