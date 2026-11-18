package com.innogrid.aims.scheduler.service;

import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import com.innogrid.aims.scheduler.entity.NodeExporterMapping;
import com.innogrid.aims.scheduler.model.NodeResource;
import com.innogrid.aims.scheduler.repository.NodeExporterMappingRepository;

import static org.junit.jupiter.api.Assertions.*;

/**
 * GPU 축의 미계측과 병합 금지 — 모의 클러스터(8노드, GPU 노드 2: seoul-02 4장·IPMI 있음, busan-03 2장·IPMI 없음)로 본다.
 *
 * <p>세 경우가 만나는 자리다: 둘 다 계측(seoul-02) · GPU만 계측(busan-03) · GPU 없음(나머지 6). 다른 단위 테스트는
 * 이 셋을 한 판에 두지 않으므로 여기서 값으로 확인한다. 원장으로 DCGM instance를 다른 노드에 넘겨 재대응이 값으로
 * 보이는 것까지 — {@code ExporterMappingCollectionTest}의 관례 그대로 리포지터리에 직접 저장한다.</p>
 */
@SpringBootTest
@TestPropertySource(properties = "aims.scheduler.mock-enabled=true")
class GpuTelemetryScenarioTest {

    @Autowired NodeResourceCollector collector;
    @Autowired NodeExporterMappingRepository repository;

    @AfterEach
    void clean() {
        repository.findAll().stream().filter(m -> m.getNodeId().startsWith("seoul-02") || m.getNodeId().startsWith("daejeon-01"))
                .forEach(repository::delete);
    }

    private Map<String, NodeResource> byId() {
        return collector.collect().nodes().stream().collect(Collectors.toMap(NodeResource::getNodeId, Function.identity()));
    }

    @DisplayName("GPU 노드 2대만 GPU 축 값이 있고 나머지 6대는 0 이다 — GPU 개수가 판별자")
    @Test
    void only_gpu_nodes_carry_telemetry() {
        Map<String, NodeResource> nodes = byId();
        assertEquals(8, nodes.size());

        NodeResource seoul02 = nodes.get("seoul-02:9100");
        assertEquals(4, seoul02.getTotalGpu());
        assertTrue(seoul02.getGpuUsagePercent() > 0 && seoul02.getGpuUsagePercent() <= 100);
        assertTrue(seoul02.getGpuTemperatureCelsius() > 35, "카드 기준 온도보다 높다");
        assertTrue(seoul02.getGpuPowerWatts() >= 4 * 60 && seoul02.getGpuPowerWatts() <= 4 * 300, "카드 4장의 합 — 유휴 이상 정격 이하");

        long noGpu = nodes.values().stream().filter(n -> n.getTotalGpu() == 0).count();
        assertEquals(6, noGpu);
        nodes.values().stream().filter(n -> n.getTotalGpu() == 0).forEach(n -> {
            assertEquals(0.0, n.getGpuUsagePercent(), n.getNodeId());
            assertEquals(0.0, n.getGpuTemperatureCelsius(), n.getNodeId());
            assertEquals(0.0, n.getGpuPowerWatts(), n.getNodeId());
        });
    }

    @DisplayName("IPMI 없는 GPU 노드는 섀시 전력·온도가 0·NONE 인 채 GPU 축만 값이 있다 — GPU 만 계측")
    @Test
    void gpu_only_node_keeps_chassis_unmeasured() {
        NodeResource busan03 = byId().get("busan-03:9100");

        assertEquals(2, busan03.getTotalGpu());
        assertEquals(NodeResource.PowerSource.NONE, busan03.getPowerSource());
        assertEquals(0.0, busan03.getPowerWatts(), "GPU 전력을 섀시 전력에 더하지 않는다");
        assertEquals(0.0, busan03.getTemperatureCelsius(), "GPU 온도를 섀시 온도에 섞지 않는다");
        assertTrue(busan03.getGpuPowerWatts() > 0);
        assertTrue(busan03.getGpuTemperatureCelsius() > 0);
    }

    @DisplayName("섀시 온도·전력은 GPU 축과 무관하게 IPMI 값 그대로다 — 병합 금지")
    @Test
    void chassis_values_are_not_contaminated_by_gpu() {
        Map<String, NodeResource> nodes = byId();
        NodeResource seoul02 = nodes.get("seoul-02:9100");
        NodeResource seoul01 = nodes.get("seoul-01:9100");

        // seoul-02의 섀시 전력은 정격 900W 이하 — GPU 전력(최대 1,200W)이 더해졌다면 넘을 수 있다
        assertTrue(seoul02.getPowerWatts() > 0 && seoul02.getPowerWatts() <= 900, "섀시 전력은 IPMI 식 범위 안");
        // 섀시 온도는 기준 42 + 0.3×CPU ≤ 72 — GPU 온도(카드 35 + 0.4×사용률)와 다른 식
        assertTrue(seoul02.getTemperatureCelsius() > 0 && seoul02.getTemperatureCelsius() <= 72);
        // 두 축이 같은 값일 수는 있어도(곡선이 교차하는 순간) 같은 식은 아니다 — 섀시 식의 상한(72)을 GPU 축은 넘을 수 있다
        assertTrue(seoul02.getGpuTemperatureCelsius() >= 35 && seoul02.getGpuTemperatureCelsius() <= 75, "GPU 축은 카드 식 35 + 0.4×사용률");
        // GPU 없는 노드의 섀시 값은 GPU 노드와 같은 식으로 나온다 — GPU 축이 섀시 계산에 끼지 않는다
        assertTrue(seoul01.getPowerWatts() > 0 && seoul01.getTemperatureCelsius() > 0);
    }

    @DisplayName("원장으로 DCGM instance 를 다른 노드에 넘기면 GPU 개수와 축이 함께 따라간다 — 재대응이 값으로 보인다")
    @Test
    void ledger_moves_the_whole_gpu_axis_with_the_dcgm_instance() {
        Map<String, NodeResource> before = byId();
        double seoul02Power = before.get("seoul-02:9100").getGpuPowerWatts();
        assertTrue(seoul02Power > 0);
        assertEquals(0, before.get("daejeon-01:9100").getTotalGpu());

        NodeExporterMapping away = new NodeExporterMapping("seoul-02:9100");
        away.setDcgmInstance("nowhere:9400");
        repository.saveAndFlush(away);
        NodeExporterMapping take = new NodeExporterMapping("daejeon-01:9100");
        take.setDcgmInstance("seoul-02:9400");
        repository.saveAndFlush(take);

        Map<String, NodeResource> after = byId();
        NodeResource seoul02 = after.get("seoul-02:9100");
        NodeResource daejeon01 = after.get("daejeon-01:9100");
        assertEquals(0, seoul02.getTotalGpu(), "가리킨 자리에 시리즈가 없어 GPU 0");
        assertEquals(0.0, seoul02.getGpuPowerWatts(), "개수 0 이면 축도 0 — 응답이 모순되지 않는다");
        assertEquals(4, daejeon01.getTotalGpu(), "seoul-02 의 카드 4장이 daejeon-01 로");
        assertTrue(daejeon01.getGpuPowerWatts() >= 4 * 60 && daejeon01.getGpuPowerWatts() <= 4 * 300, "카드 4장의 축 값이 따라간다(시각이 달라 값은 흔들린다)");
        assertTrue(daejeon01.getGpuTemperatureCelsius() > 0);
    }
}
