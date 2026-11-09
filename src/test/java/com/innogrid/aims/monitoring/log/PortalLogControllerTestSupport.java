package com.innogrid.aims.monitoring.log;

import com.innogrid.aims.dashboard.service.GpuDashboardService;
import com.innogrid.aims.infra.ipam.service.IpAddressService;
import com.innogrid.aims.infra.ipam.service.NetworkAuditService;
import com.innogrid.aims.infra.ipam.service.NetworkSubnetService;
import com.innogrid.aims.infra.ipam.service.NetworkZoneService;
import com.innogrid.aims.infra.service.RackService;
import com.innogrid.aims.infra.service.VirtualAssetService;
import com.innogrid.aims.monitoring.repository.PortalLogRepository;
import com.innogrid.aims.monitoring.service.alarm.AlarmHistoryService;
import com.innogrid.aims.monitoring.service.alarm.AlarmPolicyOptionService;
import com.innogrid.aims.monitoring.service.alarm.AlarmPolicyService;
import com.innogrid.aims.openstack.service.OpenStackTopologyDetailService;
import com.innogrid.aims.openstack.service.OpenStackTopologyService;
import com.innogrid.aims.resource.service.VmGroupService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 컨트롤러 포털 로그(@PortalLog) MockMvc 테스트의 공용 스프링 컨텍스트.
 *
 * <p>스프링은 {@code @MockitoBean} 조합이 다른 테스트 클래스마다 앱 컨텍스트를 하나씩 더 띄우고 최대 32개를 메모리에 들고 있다.
 * 묶음별 테스트(IPAM·알람·조회 전용 화면·가상서버 그룹)가 각자 가짜 빈을 두면 컨텍스트가 4개 늘어 테스트 JVM 힙(OOM)을 압박하므로,
 * 가짜 빈을 여기 한 곳에 모아 같은 컨텍스트 1개를 함께 쓴다. 새 묶음 테스트도 이 클래스를 상속하고, 필요한 가짜 빈은 여기에 더한다.</p>
 *
 * <p>가짜 빈은 테스트마다 초기화된다(Mockito 기본 reset). 실제 저장소는 공유 H2 이므로 각 테스트가 만든 로그 행을 직접 지운다.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@WithMockUser(username = "admin@innogrid.com")
public abstract class PortalLogControllerTestSupport {

    @Autowired
    protected MockMvc mockMvc;
    @Autowired
    protected PortalLogRepository repository;

    // ── 묶음 3: 네트워크(IPAM) ──
    @MockitoBean
    protected NetworkZoneService zoneService;
    @MockitoBean
    protected NetworkSubnetService subnetService;
    @MockitoBean
    protected IpAddressService ipAddressService;
    @MockitoBean
    protected NetworkAuditService auditService;

    // ── 묶음 4: 알람·알람 정책 ──
    @MockitoBean
    protected AlarmHistoryService alarmHistoryService;
    @MockitoBean
    protected AlarmPolicyService alarmPolicyService;
    @MockitoBean
    protected AlarmPolicyOptionService alarmPolicyOptionService;

    // ── 묶음 6: 가상서버 그룹 ──
    @MockitoBean
    protected VmGroupService vmGroupService;

    // ── 묶음 7: 조회 전용 화면 ──
    @MockitoBean
    protected OpenStackTopologyService topologyService;
    @MockitoBean
    protected OpenStackTopologyDetailService topologyDetailService;
    @MockitoBean
    protected RackService rackService;
    @MockitoBean
    protected VirtualAssetService virtualAssetService;
    @MockitoBean
    protected GpuDashboardService gpuDashboardService;
}
