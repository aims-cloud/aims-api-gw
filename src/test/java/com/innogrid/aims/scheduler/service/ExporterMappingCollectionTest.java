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
 * 익스포터 대응 원장이 <b>수집 결과에 실제로 닿는지</b> — 모의 클러스터로 본다.
 *
 * <p>모의 게이트웨이는 이제 DCGM 을 {@code host:9400}, IPMI 를 {@code host:9290} 으로 낸다. 노드 instance 로는 둘을
 * 찾을 수 없으므로, 수집기가 GPU 개수와 전력을 얻는다는 것 자체가 해석기가 결합을 살렸다는 증명이다. 그 위에 원장에
 * <b>다른 노드의 IPMI</b> 를 적어 두면 그 노드의 전력이 이 노드로 들어와야 한다 — 원장이 호스트명 일치보다 앞선다는
 * 것을 값으로 보이는 유일한 방법이다.</p>
 */
@SpringBootTest
@TestPropertySource(properties = "aims.scheduler.mock-enabled=true")
class ExporterMappingCollectionTest {

    @Autowired NodeResourceCollector collector;
    @Autowired NodeExporterMappingRepository repository;

    @AfterEach
    void clean() {
        repository.findAll().stream().filter(m -> m.getNodeId().startsWith("seoul-01")).forEach(repository::delete);
    }

    private Map<String, NodeResource> byId() {
        return collector.collect().nodes().stream().collect(Collectors.toMap(NodeResource::getNodeId, Function.identity()));
    }

    @DisplayName("익스포터 포트가 달라도 GPU 개수와 전력이 노드에 이어진다 — 원장 없이 호스트명 일치로")
    @Test
    void gpu_and_power_join_across_ports_without_a_ledger() {
        Map<String, NodeResource> nodes = byId();

        assertEquals(8, nodes.size());
        assertEquals(4, nodes.get("seoul-02:9100").getTotalGpu(), "DCGM 은 :9400 인데 GPU 개수가 노드에 붙어야 한다");
        assertTrue(nodes.get("seoul-01:9100").getPowerWatts() > 0, "IPMI 는 :9290 인데 전력이 노드에 붙어야 한다");
        assertEquals(NodeResource.PowerSource.NONE, nodes.get("busan-03:9100").getPowerSource(), "IPMI 없는 노드는 그대로 미계측");
    }

    @DisplayName("원장은 호스트명 일치보다 앞선다 — 다른 노드의 IPMI 를 적으면 그 노드의 전력이 들어온다")
    @Test
    void the_ledger_wins_over_the_host_heuristic() {
        Map<String, NodeResource> before = byId();
        double seoul01 = before.get("seoul-01:9100").getPowerWatts();
        double seoul03 = before.get("seoul-03:9100").getPowerWatts();
        assertTrue(seoul01 > 0 && seoul03 > 0);
        assertNotEquals(Math.round(seoul01), Math.round(seoul03), "두 노드의 전력이 달라야 바뀐 것이 보인다");

        // 호스트명 일치라면 seoul-01:9290 이 잡혔을 자리에 seoul-03 의 IPMI 를 적는다.
        NodeExporterMapping mapping = new NodeExporterMapping("seoul-01:9100");
        mapping.setIpmiInstance("seoul-03:9290");
        repository.saveAndFlush(mapping);

        Map<String, NodeResource> after = byId();
        assertEquals(Math.round(seoul03), Math.round(after.get("seoul-01:9100").getPowerWatts()),
                "원장이 가리킨 시리즈의 전력이 들어와야 한다 — 호스트명 일치가 이겼다면 값이 그대로다");
        assertEquals(Math.round(seoul03), Math.round(after.get("seoul-03:9100").getPowerWatts()), "다른 노드는 건드리지 않는다");
    }
}
