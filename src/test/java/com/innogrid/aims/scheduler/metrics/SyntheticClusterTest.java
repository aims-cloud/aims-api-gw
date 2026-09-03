package com.innogrid.aims.scheduler.metrics;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import static org.junit.jupiter.api.Assertions.*;

/** 가상 클러스터 파일 로딩·검증·결정성. */
class SyntheticClusterTest {

    @DisplayName("기본 파일은 MDC 3곳·노드 8대이고 IPMI 없는 노드 3·GPU 노드 2·디스크 부족 노드 1 을 담는다")
    @Test
    void default_file_shape() {
        SyntheticCluster c = SyntheticCluster.load("classpath:scheduler/synthetic-cluster.yml");
        assertEquals("demo-3mdc-8nodes", c.name());
        assertEquals("mdc-nodes", c.job());
        assertEquals(8, c.nodes().size());
        assertEquals(3, c.mdcCount());
        assertEquals(3, c.nodes().stream().filter(n -> !n.ipmi()).count());
        assertEquals(2, c.nodes().stream().filter(n -> n.gpus() > 0).count());
        assertEquals(1, c.nodes().stream().filter(n -> n.diskUsage() > 90).count());
        assertTrue(c.node("seoul-03:9100").orElseThrow().baseTemperature() >= 60, "뜨거운 노드");
        assertTrue(c.node("busan-02:9100").orElseThrow().idleWatts() > 300, "전력 비효율 노드");
    }

    @DisplayName("같은 노드·같은 시각이면 값이 같고, 노드마다 위상이 달라 같은 시각에도 다른 값이 나온다")
    @Test
    void deterministic_and_node_specific() {
        SyntheticCluster c = SyntheticCluster.load("classpath:scheduler/synthetic-cluster.yml");
        SyntheticCluster.Node a = c.node("seoul-01:9100").orElseThrow();
        SyntheticCluster.Node b = c.node("busan-01:9100").orElseThrow();
        long t = 1_800_000_000L;
        assertEquals(c.cpuUsage(a, t), c.cpuUsage(a, t));
        assertNotEquals(c.phase(a), c.phase(b));
        assertTrue(c.cpuUsage(a, t) >= 0 && c.cpuUsage(a, t) <= 100);
        // 전력은 CPU 를 따라간다: 유휴 ≤ 전력 ≤ 정격
        assertTrue(c.powerWatts(a, t) >= a.idleWatts() && c.powerWatts(a, t) <= a.capacityWatts());
        // 한 주기(60분) 뒤에는 같은 값
        assertEquals(c.cpuUsage(a, t), c.cpuUsage(a, t + 3600), 1e-9);
        assertNotEquals(c.cpuUsage(a, t), c.cpuUsage(a, t + 900), "15분 뒤엔 다른 값");
    }

    @DisplayName("잘못된 파일 — 노드 없음·nodeId 중복·cores 0·사용률 범위 밖은 IllegalArgumentException")
    @Test
    void validation_errors() {
        assertThrows(IllegalArgumentException.class, () -> SyntheticCluster.parse(Map.of("nodes", List.of()), "x"));
        assertThrows(IllegalArgumentException.class, () -> SyntheticCluster.parse(new Yaml().load(
                "nodes:\n  - {nodeId: a, cores: 4, memoryMb: 1024}\n  - {nodeId: a, cores: 4, memoryMb: 1024}\n"), "x"));
        assertThrows(IllegalArgumentException.class, () -> SyntheticCluster.parse(new Yaml().load(
                "nodes:\n  - {nodeId: a, cores: 0, memoryMb: 1024}\n"), "x"));
        assertThrows(IllegalArgumentException.class, () -> SyntheticCluster.parse(new Yaml().load(
                "nodes:\n  - {nodeId: a, cores: 4, memoryMb: 1024, cpuUsage: 150}\n"), "x"));
        assertThrows(IllegalArgumentException.class, () -> SyntheticCluster.load("classpath:scheduler/does-not-exist.yml"));
    }

    @DisplayName("생략한 값은 기본값 — mdc-01·IPMI 있음·정격 500W·GPU 0")
    @Test
    void defaults() {
        SyntheticCluster c = SyntheticCluster.parse(new Yaml().load("nodes:\n  - {nodeId: n1, cores: 4, memoryMb: 1024}\n"), "x");
        SyntheticCluster.Node n = c.nodes().get(0);
        assertEquals("mdc-01", n.mdcId());
        assertTrue(n.ipmi());
        assertEquals(500.0, n.capacityWatts());
        assertEquals(0, n.gpus());
        assertEquals("mdc-nodes", c.job());
    }
}
