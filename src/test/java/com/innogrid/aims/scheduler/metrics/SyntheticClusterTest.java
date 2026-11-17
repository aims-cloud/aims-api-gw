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
        assertTrue(c.node("seoul-03:9100").orElseThrow().baseTemperature() >= 50, "뜨거운 노드");
        assertEquals(1.0, c.swingOf(c.node("busan-02:9100").orElseThrow()), "유휴 노드는 진폭 1 — 가동 판정(5%) 아래 유지");
        assertEquals(15.0, c.swingOf(c.node("seoul-01:9100").orElseThrow()), "나머지는 공통 진폭");
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
        // 전력은 CPU를 따라간다: 유휴 ≤ 전력 ≤ 정격
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
        assertEquals(1.0, c.powerExponent(), "지수를 적지 않은 파일은 예전 그대로 선형이다");
        assertEquals(n.capacityWatts(), n.believedCapacityWatts(),
                "엔진에 알려 줄 정격을 적지 않으면 생성식 정격과 같다 — 기존 판이 그대로여야 한다");
    }

    @DisplayName("엔진에 알려 줄 정격은 0 보다 크고 유휴 전력보다 커야 한다")
    @Test
    void believed_capacity_must_exceed_the_idle_draw() {
        assertEquals(450.0, SyntheticCluster.parse(new Yaml().load(
                "nodes:\n  - {nodeId: a, cores: 4, memoryMb: 1024, capacityWatts: 500, believedCapacityWatts: 450}\n"), "x")
                .nodes().get(0).believedCapacityWatts(), "적은 값이 그대로 실린다");
        assertThrows(IllegalArgumentException.class, () -> SyntheticCluster.parse(new Yaml().load(
                "nodes:\n  - {nodeId: a, cores: 4, memoryMb: 1024, believedCapacityWatts: 0}\n"), "x"),
                "0 이면 엔진의 여유 계산이 0 나누기가 된다");
        assertThrows(IllegalArgumentException.class, () -> SyntheticCluster.parse(new Yaml().load(
                "nodes:\n  - {nodeId: a, cores: 4, memoryMb: 1024, idleWatts: 150, believedCapacityWatts: 120}\n"), "x"),
                "유휴보다 작으면 엔진이 믿는 세계에서 유휴 노드가 이미 정격을 넘는다");
    }

    // ── 전력 생성식의 지수 ────────────────────────────────────

    @DisplayName("지수는 0 보다 큰 유한한 값이어야 한다")
    @Test
    void power_exponent_must_be_positive() {
        assertEquals(1.6, SyntheticCluster.parse(new Yaml().load(
                "powerExponent: 1.6\nnodes:\n  - {nodeId: a, cores: 4, memoryMb: 1024}\n"), "x").powerExponent());
        assertThrows(IllegalArgumentException.class, () -> SyntheticCluster.parse(new Yaml().load(
                "powerExponent: 0\nnodes:\n  - {nodeId: a, cores: 4, memoryMb: 1024}\n"), "x"),
                "0 이면 전 노드가 부하와 무관하게 정격을 먹는다 — 판이 아니라 오류다");
        assertThrows(IllegalArgumentException.class, () -> SyntheticCluster.parse(new Yaml().load(
                "powerExponent: -0.5\nnodes:\n  - {nodeId: a, cores: 4, memoryMb: 1024}\n"), "x"),
                "음수면 0% 에서 전력이 무한대가 된다");
    }

    /**
     * 지수를 일반화하면서 <b>기존 판의 값이 한 비트도 움직이지 않았는지</b> 본다. 예전 식은 좌결합
     * {@code ((정격−유휴) × cpu) / 100}이라 괄호를 옮기기만 해도 마지막 비트가 달라지고, 그러면 판
     * 다이제스트는 같은데 숫자가 미세하게 바뀐 표가 나온다. 그래서 오차 허용 없이 {@code ==}로 잠근다.
     */
    @DisplayName("지수가 1 인 클러스터의 전력은 예전 식과 비트까지 같다")
    @Test
    void a_linear_cluster_reproduces_the_old_formula_bit_for_bit() {
        SyntheticCluster c = SyntheticCluster.load("classpath:scheduler/synthetic-cluster.yml");
        long base = 1_800_000_000L;
        long step = c.periodMinutes() * 60L / 12;

        for (SyntheticCluster.Node n : c.nodes()) {
            for (int i = 0; i < 12; i++) {
                long t = base + step * i;
                double expected = n.idleWatts()
                        + (n.capacityWatts() - n.idleWatts()) * c.cpuUsage(n, t) / 100.0;
                assertEquals(expected, c.powerWatts(n, t),
                        () -> n.nodeId() + " @" + t + " — 지수 일반화가 기존 판의 값을 흔들면 안 된다");
            }
        }
    }
}
