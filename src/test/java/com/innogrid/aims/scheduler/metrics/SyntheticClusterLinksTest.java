package com.innogrid.aims.scheduler.metrics;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import com.innogrid.aims.scheduler.benchmark.BenchmarkClusters;
import com.innogrid.aims.scheduler.entity.MdcLink;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 판 파일의 {@code links:} 절 — 노드 절과 갈라진 파서가 링크를 읽고, 절이 없는 판은 아무것도 달라지지 않는지.
 *
 * <p>등록된 판 가운데 링크 판만 링크를 선언한다. 그래서 이 테스트는 링크 판에만 링크가 있고 나머지 판은 비어 있는지와,
 * 판 파일이 진실이 되도록 파서가 받는 형태와 거부하는 형태를 확인한다.</p>
 */
class SyntheticClusterLinksTest {

    private static final String NODES = """
            name: t
            nodes:
              - { mdcId: a, nodeId: "n-a:9100", cores: 8, memoryMb: 32768, idleWatts: 100, capacityWatts: 400 }
              - { mdcId: b, nodeId: "n-b:9100", cores: 8, memoryMb: 32768, idleWatts: 100, capacityWatts: 400 }
              - { mdcId: c, nodeId: "n-c:9100", cores: 8, memoryMb: 32768, idleWatts: 100, capacityWatts: 400 }
            """;

    @DisplayName("링크 판만 links 절과 데이터 MDC 순환 배정을 선언하고 나머지 판은 둘 다 비어 있다")
    @Test
    void only_the_link_board_declares_links() {
        BenchmarkClusters clusters = new BenchmarkClusters();
        for (String id : clusters.boardIds()) {
            SyntheticCluster c = clusters.get(id);
            if (BenchmarkClusters.LINK.equals(id)) {
                assertEquals(2, c.links().size(), "링크 판 — 등록 두 쌍(UP·DEGRADED), 나머지 한 쌍은 미등록");
                assertEquals(List.of("bm-mdc-a", "bm-mdc-b", "bm-mdc-c"), c.workloadDataMdcIds());
            } else {
                assertTrue(c.links().isEmpty(), id + " — 링크 절이 생기면 이 시험과 다이제스트 리터럴이 함께 깨져야 한다");
                assertTrue(c.workloadDataMdcIds().isEmpty(), id + " — 데이터 MDC 를 부여하면 게이트가 켜져 기준선이 바뀐다");
            }
        }
    }

    @DisplayName("workloadDataMdcIds 는 판의 노드 MDC 여야 하고 중복·빈 목록은 거부한다")
    @Test
    void rejects_malformed_workload_data_mdc_ids() {
        for (String bad : List.of("workloadDataMdcIds: [a, z]\n", "workloadDataMdcIds: [a, a]\n", "workloadDataMdcIds: []\n")) {
            Object root = new Yaml().load(NODES + bad);
            assertThrows(IllegalArgumentException.class, () -> SyntheticCluster.parse(root, "test"), bad);
        }
        SyntheticCluster ok = SyntheticCluster.parse(new Yaml().load(NODES + "workloadDataMdcIds: [b, a]\n"), "test");
        assertEquals(List.of("b", "a"), ok.workloadDataMdcIds(), "선언 순서가 배정 순서다");
    }

    @DisplayName("links 절을 읽는다 — 필수 셋과 선택 둘, 상태 기본 UP")
    @Test
    void parses_links_section() {
        SyntheticCluster c = SyntheticCluster.parse(new Yaml().load(NODES + """
                links:
                  - { mdcA: a, mdcB: b, latencyMs: 20 }
                  - { mdcA: a, mdcB: c, latencyMs: 5, bandwidthMbps: 1000, packetLossPercent: 0.5, status: degraded }
                """), "test");

        assertEquals(2, c.links().size());
        SyntheticCluster.Link first = c.links().get(0);
        assertEquals("a", first.mdcA()); assertEquals("b", first.mdcB());
        assertEquals(20.0, first.latencyMs()); assertNull(first.bandwidthMbps()); assertEquals(MdcLink.LinkStatus.UP, first.status());
        SyntheticCluster.Link second = c.links().get(1);
        assertEquals(1000.0, second.bandwidthMbps()); assertEquals(0.5, second.packetLossPercent()); assertEquals(MdcLink.LinkStatus.DEGRADED, second.status());
        assertEquals(3, c.nodes().size(), "노드 절은 그대로 읽힌다");
    }

    @DisplayName("같은 MDC·중복 쌍·지연 누락·모르는 상태·판에 없는 MDC·범위 밖 값은 거부한다")
    @Test
    void rejects_malformed_links() {
        for (String bad : List.of(
                "links:\n  - { mdcA: a, mdcB: a, latencyMs: 1 }\n",
                "links:\n  - { mdcA: a, mdcB: b, latencyMs: 1 }\n  - { mdcA: b, mdcB: a, latencyMs: 2 }\n",
                "links:\n  - { mdcA: a, mdcB: b }\n",
                "links:\n  - { mdcA: a, mdcB: b, latencyMs: 1, status: BROKEN }\n",
                "links:\n  - { mdcA: a, mdcB: z, latencyMs: 1 }\n",
                "links:\n  - { mdcA: a, mdcB: b, latencyMs: 1, bandwidthMbps: 0 }\n",
                "links:\n  - { mdcA: a, mdcB: b, latencyMs: 1, packetLossPercent: 101 }\n",
                "links:\n  - a-b\n")) {
            Object root = new Yaml().load(NODES + bad);
            assertThrows(IllegalArgumentException.class, () -> SyntheticCluster.parse(root, "test"), bad);
        }
    }

    @DisplayName("쌍은 원장 규칙으로 정렬해 담는다 — b,a 로 적어도 a,b 와 같은 레코드")
    @Test
    void pairs_are_normalized_like_the_ledger() {
        SyntheticCluster c = SyntheticCluster.parse(new Yaml().load(NODES + "links:\n  - { mdcA: b, mdcB: a, latencyMs: 3 }\n"), "test");
        SyntheticCluster.Link l = c.links().get(0);
        assertEquals("a", l.mdcA()); assertEquals("b", l.mdcB());
        assertEquals(MdcLink.Pair.of("b", "a"), new MdcLink.Pair(l.mdcA(), l.mdcB()));
    }

    @DisplayName("links 절이 없는 판은 파싱 결과가 예전과 같다")
    @Test
    void absent_links_section_is_neutral() {
        Object root = new Yaml().load(NODES);
        SyntheticCluster c = SyntheticCluster.parse(root, "test");
        assertTrue(c.links().isEmpty());
        assertEquals(3, c.nodes().size());
        assertTrue(root instanceof Map<?, ?> m && !m.containsKey("links"));
        assertTrue(c.workloadBandwidthMbps().isEmpty(), "요구 대역폭 절도 없으면 비어 있다");
    }

    @DisplayName("workloadBandwidthMbps 를 읽고 0 은 요구 없음으로 남기며 음수와 빈 목록은 거부한다")
    @Test
    void parses_workload_bandwidth_section() {
        SyntheticCluster c = SyntheticCluster.parse(
                new Yaml().load(NODES + "workloadBandwidthMbps: [0, 500, 2000]\n"), "test");
        assertEquals(List.of(0.0, 500.0, 2000.0), c.workloadBandwidthMbps());

        SyntheticCluster dup = SyntheticCluster.parse(
                new Yaml().load(NODES + "workloadBandwidthMbps: [500, 500]\n"), "test");
        assertEquals(List.of(500.0, 500.0), dup.workloadBandwidthMbps(), "데이터 MDC 와 달리 중복을 허용한다");

        assertThrows(IllegalArgumentException.class,
                () -> SyntheticCluster.parse(new Yaml().load(NODES + "workloadBandwidthMbps: [-1]\n"), "test"));
        assertThrows(IllegalArgumentException.class,
                () -> SyntheticCluster.parse(new Yaml().load(NODES + "workloadBandwidthMbps: []\n"), "test"));
    }

    @DisplayName("링크 판만 요구 대역폭을 선언하고 링크 두 쌍에 대역폭이 있다")
    @Test
    void only_the_link_board_declares_required_bandwidth() {
        BenchmarkClusters clusters = new BenchmarkClusters();
        for (String id : clusters.boardIds()) {
            SyntheticCluster c = clusters.get(id);
            if (BenchmarkClusters.LINK.equals(id)) {
                assertEquals(List.of(0.0, 500.0, 2000.0), c.workloadBandwidthMbps(), id);
                assertEquals(List.of(10000.0, 1000.0), c.links().stream().map(SyntheticCluster.Link::bandwidthMbps).toList(), id);
            } else {
                assertTrue(c.workloadBandwidthMbps().isEmpty(), id + " 는 요구 대역폭을 선언하지 않는다");
            }
        }
    }

    @DisplayName("시드 사본도 요구 대역폭 배정 목록을 그대로 물려받는다")
    @Test
    void seed_copy_keeps_the_bandwidth_assignment() {
        SyntheticCluster c = SyntheticCluster.parse(
                new Yaml().load(NODES + "workloadBandwidthMbps: [0, 500]\n"), "test");
        assertEquals(c.workloadBandwidthMbps(), c.withSeed(99).workloadBandwidthMbps());
    }
}
