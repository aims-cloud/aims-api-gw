package com.innogrid.aims.dashboard.service;

import java.util.List;
import java.util.Set;
import com.innogrid.aims.auth.entity.Role;
import com.innogrid.aims.auth.service.AuthService;
import com.innogrid.aims.dashboard.service.GpuNodeScopeService.GpuNode;
import com.innogrid.aims.dashboard.service.GpuNodeScopeService.GpuNodeScope;
import com.innogrid.aims.infra.entity.Asset;
import com.innogrid.aims.infra.entity.RoleAsset;
import com.innogrid.aims.infra.repository.RoleAssetRepository;
import com.innogrid.aims.monitoring.entity.MonitoringTarget;
import com.innogrid.aims.monitoring.entity.MonitoringTarget.ExporterType;
import com.innogrid.aims.monitoring.repository.MonitoringTargetRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.*;

/** 역할 스코프 → 호스트 자산 → DCGM 수집 대상. 역할 필터는 토큰 역할 안에서만, 배포영역은 자산 MDC, ADMIN 판정은 토큰 기준. */
class GpuNodeScopeServiceTest {

    AuthService auth = mock(AuthService.class);
    RoleAssetRepository roleAssets = mock(RoleAssetRepository.class);
    MonitoringTargetRepository targets = mock(MonitoringTargetRepository.class);
    GpuNodeScopeService service = new GpuNodeScopeService(auth, roleAssets, targets);

    Role admin = new Role("ADMIN", "관리자");
    Role user = new Role("USER", "사용자");
    Asset gpu1 = asset(1L, "mdc-01", Asset.AssetType.GPU_SERVER, "gpu-node-1");
    Asset gpu2 = asset(2L, "mdc-02", Asset.AssetType.GPU_SERVER, "gpu-node-2");
    Asset sw = asset(3L, "mdc-01", Asset.AssetType.SWITCH, "switch-1");

    static Asset asset(long id, String mdc, Asset.AssetType type, String name) {
        Asset a = new Asset(mdc, type, name);
        ReflectionTestUtils.setField(a, "id", id);
        return a;
    }

    static MonitoringTarget target(Asset asset, String address, int port, ExporterType type) {
        MonitoringTarget t = new MonitoringTarget(asset.getMdcId(), asset.getName(), address, port, type);
        t.setAsset(asset);
        return t;
    }

    @BeforeEach void setup() {
        when(roleAssets.findByRole_NameIn(anyCollection())).thenAnswer(inv -> {
            java.util.Collection<String> names = inv.getArgument(0);
            return List.of(new RoleAsset(admin, gpu1), new RoleAsset(admin, gpu2), new RoleAsset(user, gpu1),
                            new RoleAsset(admin, sw)).stream()
                    .filter(m -> names.contains(m.getRole().getName())).toList();
        });
        when(targets.findByAsset_IdIn(anyCollection())).thenAnswer(inv -> {
            java.util.Collection<Long> ids = inv.getArgument(0);
            return List.of(target(gpu1, "10.0.0.11", 9400, ExporterType.DCGM_EXPORTER),
                            target(gpu1, "10.0.0.11", 9100, ExporterType.NODE_EXPORTER),
                            target(gpu2, "10.0.0.12", 9400, ExporterType.DCGM_EXPORTER)).stream()
                    .filter(t -> ids.contains(t.getAsset().getId())).toList();
        });
    }

    @Test void adminSeesAllGpuNodesByDcgmTargetOnly() {
        when(auth.currentUserRoleIds()).thenReturn(Set.of("ADMIN"));
        GpuNodeScope scope = service.currentScope(null, null);
        assertThat(scope.admin()).isTrue();
        assertThat(scope.nodes()).extracting(GpuNode::instance).containsExactly("10.0.0.11:9400", "10.0.0.12:9400");
        assertThat(scope.byInstance().get("10.0.0.11:9400").mdcId()).isEqualTo("mdc-01");
        assertThat(scope.mdcIds()).containsExactly("mdc-01", "mdc-02");
    }

    @Test void roleFilterCannotWidenTokenRolesAndDeploymentFilterUsesAssetMdc() {
        when(auth.currentUserRoleIds()).thenReturn(Set.of("USER"));
        GpuNodeScope widened = service.currentScope(List.of("ADMIN", "USER"), null);
        assertThat(widened.admin()).isFalse();
        assertThat(widened.roles()).containsExactly("USER");                              // ADMIN 은 토큰에 없어 무시
        assertThat(widened.nodes()).extracting(GpuNode::assetName).containsExactly("gpu-node-1");

        GpuNodeScope foreign = service.currentScope(List.of("ADMIN"), null);
        assertThat(foreign.nodes()).isEmpty();                                            // 토큰 밖 역할만 고르면 아무것도 없음
        verify(roleAssets, times(1)).findByRole_NameIn(anyCollection());                  // 빈 역할은 조회하지 않는다

        when(auth.currentUserRoleIds()).thenReturn(Set.of("ADMIN"));
        assertThat(service.currentScope(null, List.of("mdc-02")).nodes()).extracting(GpuNode::assetName)
                .containsExactly("gpu-node-2");
    }

    @Test void nodeKeepsEveryVisibleRole() {
        GpuNodeScope scope = service.scopeOf(Set.of("ADMIN", "USER"), null, null);
        GpuNode node = scope.byInstance().get("10.0.0.11:9400");
        assertThat(node.roles()).containsExactlyInAnyOrder("ADMIN", "USER");
        assertThat(node.primaryRole()).isEqualTo("ADMIN");
    }

    @Test void registeredInstancesAreAllDcgmTargetsRegardlessOfRole() {
        when(targets.findByExporterType(ExporterType.DCGM_EXPORTER)).thenReturn(List.of(
                target(gpu1, "10.0.0.11", 9400, ExporterType.DCGM_EXPORTER),
                target(gpu2, "10.0.0.12", 9400, ExporterType.DCGM_EXPORTER)));
        assertThat(service.registeredInstances()).containsExactly("10.0.0.11:9400", "10.0.0.12:9400");
    }
}
