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
 * <p>모의 게이트웨이는 DCGM을 {@code host:9400}, IPMI를 {@code host:9290}으로 낸다. 노드 instance로는 둘을
 * 찾을 수 없으므로, 수집기가 GPU 개수와 전력을 얻는다는 것 자체가 해석기가 결합을 살렸다는 증명이다. 그 위에 원장에
 * <b>저장소에 없는 IPMI</b>를 적어 두면 호스트명 일치로 잡혔을 전력이 사라져야 한다 — 원장이 호스트명 일치보다
 * 앞선다는 것을 값으로 보이는 방법이다. 다른 노드의 시리즈를 적으면 두 노드가 충돌해 둘 다 미계측이 된다 — 한 값을 두
 * 번 세지 않는다.</p>
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

    @DisplayName("원장은 호스트명 일치보다 앞선다 — 저장소에 없는 IPMI 를 적으면 잡혔을 전력이 사라진다")
    @Test
    void the_ledger_wins_over_the_host_heuristic() {
        Map<String, NodeResource> before = byId();
        assertTrue(before.get("seoul-01:9100").getPowerWatts() > 0, "호스트명 일치로 seoul-01:9290 이 잡혀 있어야 출발점이 된다");

        NodeExporterMapping mapping = new NodeExporterMapping("seoul-01:9100");
        mapping.setIpmiInstance("nowhere:9290");
        repository.saveAndFlush(mapping);

        Map<String, NodeResource> after = byId();
        assertEquals(0.0, after.get("seoul-01:9100").getPowerWatts(), "원장이 가리킨 자리에는 시리즈가 없다 — 호스트명 일치가 이겼다면 값이 그대로다");
        assertEquals(NodeResource.PowerSource.NONE, after.get("seoul-01:9100").getPowerSource());
        assertTrue(after.get("seoul-03:9100").getPowerWatts() > 0, "다른 노드는 건드리지 않는다");
    }

    @DisplayName("두 노드가 같은 IPMI 시리즈를 가리키면 둘 다 미계측이다 — 한 전력을 두 번 세지 않는다")
    @Test
    void two_nodes_on_one_series_are_both_unmeasured() {
        Map<String, NodeResource> before = byId();
        double seoul03 = before.get("seoul-03:9100").getPowerWatts();
        assertTrue(seoul03 > 0);

        // seoul-03은 호스트명 일치로 seoul-03:9290을 갖는다. seoul-01의 원장이 같은 시리즈를 가리키면 충돌이다.
        NodeExporterMapping mapping = new NodeExporterMapping("seoul-01:9100");
        mapping.setIpmiInstance("seoul-03:9290");
        repository.saveAndFlush(mapping);

        Map<String, NodeResource> after = byId();
        assertEquals(NodeResource.PowerSource.NONE, after.get("seoul-01:9100").getPowerSource(), "가리킨 쪽");
        assertEquals(NodeResource.PowerSource.NONE, after.get("seoul-03:9100").getPowerSource(), "가리켜진 쪽도 — 어느 값이 맞는지 알 수 없다");
        double sumBefore = before.values().stream().mapToDouble(NodeResource::getPowerWatts).sum();
        double sumAfter = after.values().stream().mapToDouble(NodeResource::getPowerWatts).sum();
        assertTrue(sumAfter < sumBefore, "합계는 줄어야 한다 — 두 배로 세었다면 커진다");
    }
}
