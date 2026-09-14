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
 * <p>지금 등록된 판에는 링크가 없다. 그래서 이 시험의 절반은 "없을 때 비어 있다" 이고, 나머지 절반은 링크 판이 들어올 때
 * 파일이 진실이 되도록 파서가 받는 형태와 거부하는 형태를 미리 고정하는 것이다.</p>
 */
class SyntheticClusterLinksTest {

    private static final String NODES = """
            name: t
            nodes:
              - { mdcId: a, nodeId: "n-a:9100", cores: 8, memoryMb: 32768, idleWatts: 100, capacityWatts: 400 }
              - { mdcId: b, nodeId: "n-b:9100", cores: 8, memoryMb: 32768, idleWatts: 100, capacityWatts: 400 }
              - { mdcId: c, nodeId: "n-c:9100", cores: 8, memoryMb: 32768, idleWatts: 100, capacityWatts: 400 }
            """;

    @DisplayName("등록된 판 전부는 links 절이 없어 빈 목록이다")
    @Test
    void registered_boards_declare_no_links() {
        BenchmarkClusters clusters = new BenchmarkClusters();
        for (String id : clusters.boardIds()) {
            assertTrue(clusters.get(id).links().isEmpty(), id + " — 링크 절이 생기면 이 시험과 다이제스트 리터럴이 함께 깨져야 한다");
        }
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
    }
}
