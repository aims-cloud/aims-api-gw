package com.innogrid.aims.scheduler.metrics;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.Optional;

import com.innogrid.aims.scheduler.entity.MdcLink;

import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.core.io.Resource;
import org.yaml.snakeyaml.Yaml;

/**
 * 가상 클러스터 — 1세부 데이터가 없는 동안 스케줄러가 볼 노드들의 정의와, 시간에 따라 값을 만드는 결정적 함수.
 *
 * <p>YAML 한 장이 MDC·노드·기준값을 준다. 값은 <b>시각의 함수</b>라 같은 시각·같은 파일이면 언제 물어도 같다(재현 가능).
 * 사인 곡선(주기 {@code periodMinutes}) 위에 노드별 위상을 얹어 노드마다 다른 곡선을 만들고, 진폭은 {@code swing}
 * (사용률 %p)이다. 전력은 유휴 전력 + (정격 − 유휴) × (CPU 사용률/100)^{@code powerExponent}(기본 1 — 선형)로
 * CPU 를 따라가고, 온도는 기준 온도 + CPU 기여로 따라간다.
 * {@code ipmi: false} 인 노드는 전력·온도 메트릭이 <b>없다</b>(실환경의 미계측 노드 재현).</p>
 *
 * <p>정격 전력에는 두 값이 있다. <b>수집 경로</b>(운영 모의 모드)에서 배치 엔진이 보는 정격은 설정
 * {@code capacityByNode}·{@code defaultCapacityWatts} 에서 오고, 이 파일의 {@code capacityWatts} 는 생성식이
 * 전력을 만들 때만 쓴다. <b>판 경로</b>(배치 비교)에서는 이 파일이 원장 노릇을 한다 — 엔진이 보는 정격도
 * 파일에서 오며 그 값은 {@code believedCapacityWatts}(적지 않으면 {@code capacityWatts})다. 그래서 판
 * 경로에서는 설정의 정격이 읽히지 않는다.</p>
 */
public final class SyntheticCluster {

    /**
     * 노드 한 대의 기준값. 사용률은 %(0~100), 메모리 MB, 디스크 GB, 네트워크 bytes/s.
     *
     * <p>{@code capacityWatts} 는 <b>생성식이 쓰는 정격</b>이고, {@code believedCapacityWatts} 는
     * <b>배치 엔진에게 알려 주는 정격</b>이다. 둘을 가른 이유는 엔진이 정격을 틀리게 알고 있을 때
     * 무엇이 흔들리는지를 보는 판이 필요해서다 — 적지 않으면 두 값이 같아 예전 판과 다르지 않다.</p>
     */
    public record Node(String mdcId, String nodeId, int cores, long memoryMb, int gpus,
                       double cpuUsage, double memoryUsage, double diskTotalGb, double diskUsage,
                       boolean ipmi, double idleWatts, double capacityWatts, double believedCapacityWatts,
                       double baseTemperature,
                       double networkRxBps, double networkTxBps, String trait, Double swing) {

        double memoryTotalBytes() { return memoryMb * 1024.0 * 1024.0; }
        double diskTotalBytes() { return diskTotalGb * 1024.0 * 1024.0 * 1024.0; }
    }

    /**
     * 판이 선언하는 MDC 사이 링크 한 줄 — 원장 엔티티({@code MdcLink})의 판 파일 표현.
     *
     * <p>지금 등록된 판에는 {@code links:} 절이 없어 이 목록은 비어 있고, 아무 계산도 읽지 않는다. 링크 판이 들어올 때
     * 판 파일이 진실이 되도록 <b>파서만 먼저</b> 갈라 둔다 — 노드 절과 링크 절은 서로 다른 축이라 한 파서에 섞이면
     * 판을 늘릴 때마다 같은 메서드를 두 번 연다. 절이 없는 판은 파일 바이트가 그대로라 다이제스트·값이 변하지 않는다.</p>
     */
    public record Link(String mdcA, String mdcB, double latencyMs, Double bandwidthMbps, Double packetLossPercent,
                       MdcLink.LinkStatus status) {}

    private final String name;
    private final String job;
    private final int periodMinutes;
    private final double swing;
    private final long seed;
    private final double powerExponent;
    private final List<Node> nodes;
    private final List<Link> links;

    SyntheticCluster(String name, String job, int periodMinutes, double swing, long seed,
                     double powerExponent, List<Node> nodes) {
        this(name, job, periodMinutes, swing, seed, powerExponent, nodes, List.of());
    }

    SyntheticCluster(String name, String job, int periodMinutes, double swing, long seed,
                     double powerExponent, List<Node> nodes, List<Link> links) {
        this.links = List.copyOf(links);
        this.name = name; this.job = job; this.periodMinutes = periodMinutes; this.swing = swing; this.seed = seed;
        this.powerExponent = powerExponent;
        this.nodes = List.copyOf(nodes);
    }

    // ── 로딩 ──────────────────────────────────────────────────

    /** {@code classpath:} 또는 파일 경로에서 읽는다. 잘못된 파일은 {@link IllegalArgumentException}. */
    public static SyntheticCluster load(String location) {
        Resource resource = new DefaultResourceLoader().getResource(location);
        try (InputStream in = resource.getInputStream()) {
            return parse(new Yaml().load(in), location);
        } catch (IOException e) {
            throw new IllegalArgumentException("가상 클러스터 파일을 읽을 수 없습니다: " + location, e);
        }
    }

    @SuppressWarnings("unchecked")
    static SyntheticCluster parse(Object root, String location) {
        if (!(root instanceof Map<?, ?> m)) throw new IllegalArgumentException("가상 클러스터 파일이 비었습니다: " + location);
        Map<String, Object> map = (Map<String, Object>) m;
        String name = str(map.get("name"), "synthetic");
        String job = str(map.get("job"), "mdc-nodes");
        int period = num(map.get("periodMinutes"), 60).intValue();
        double swing = num(map.get("swing"), 15).doubleValue();
        long seed = num(map.get("seed"), 7).longValue();
        double powerExponent = num(map.get("powerExponent"), 1.0).doubleValue();
        if (!(powerExponent > 0) || !Double.isFinite(powerExponent)) {
            throw new IllegalArgumentException("powerExponent 는 0 보다 큰 유한한 값이어야 합니다: " + location + " = " + powerExponent);
        }
        List<Node> nodes = parseNodes(map.get("nodes"), swing, location);
        Set<String> mdcIds = nodes.stream().map(Node::mdcId).collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        List<Link> links = parseLinks(map.get("links"), mdcIds, location);
        return new SyntheticCluster(name, job, period, swing, seed, powerExponent, nodes, links);
    }

    /** {@code nodes:} 절 — 판의 노드 축. 비어 있으면 판이 아니다. */
    @SuppressWarnings("unchecked")
    static List<Node> parseNodes(Object rawNodes, double swing, String location) {
        if (!(rawNodes instanceof List<?> list) || list.isEmpty()) throw new IllegalArgumentException("nodes 가 비었습니다: " + location);
        List<Node> nodes = new ArrayList<>();
        LinkedHashSet<String> ids = new LinkedHashSet<>();
        for (Object o : list) {
            Map<String, Object> n = (Map<String, Object>) o;
            String nodeId = str(n.get("nodeId"), null);
            if (nodeId == null || nodeId.isBlank()) throw new IllegalArgumentException("nodeId 가 없는 노드가 있습니다");
            if (!ids.add(nodeId)) throw new IllegalArgumentException("nodeId 중복: " + nodeId);
            int cores = num(n.get("cores"), 0).intValue();
            long memoryMb = num(n.get("memoryMb"), 0).longValue();
            if (cores <= 0 || memoryMb <= 0) throw new IllegalArgumentException("cores·memoryMb 는 0 보다 커야 합니다: " + nodeId);
            double cap = num(n.get("capacityWatts"), 500).doubleValue();
            double idle = num(n.get("idleWatts"), 150).doubleValue();
            double believed = num(n.get("believedCapacityWatts"), cap).doubleValue();
            if (!(believed > 0) || believed <= idle) {
                throw new IllegalArgumentException("엔진에 알려 줄 정격은 0 보다 크고 유휴 전력보다 커야 합니다: "
                        + nodeId + " (알려 줄 정격 " + believed + "W, 유휴 " + idle + "W)");
            }
            nodes.add(new Node(str(n.get("mdcId"), "mdc-01"), nodeId, cores, memoryMb, num(n.get("gpus"), 0).intValue(),
                    pct(n.get("cpuUsage"), 20, nodeId), pct(n.get("memoryUsage"), 40, nodeId),
                    num(n.get("diskTotalGb"), 500).doubleValue(), pct(n.get("diskUsage"), 30, nodeId),
                    bool(n.get("ipmi"), true), idle, cap, believed,
                    num(n.get("baseTemperature"), 40).doubleValue(),
                    num(n.get("networkRxBps"), 1_000_000).doubleValue(), num(n.get("networkTxBps"), 800_000).doubleValue(),
                    str(n.get("trait"), ""), n.get("swing") == null ? null : num(n.get("swing"), swing).doubleValue()));
        }
        return nodes;
    }

    /**
     * {@code links:} 절 — 판의 링크 축. 절이 없으면 빈 목록(지금 등록된 판 전부). 항목은 {@code mdcA}·{@code mdcB}·
     * {@code latencyMs} 가 필수이고 {@code bandwidthMbps}(0 초과)·{@code packetLossPercent}(0~100) 는 선택, {@code status} 는
     * 원장 열거형 {@link MdcLink.LinkStatus}({@code UP} 기본). 같은 MDC 사이 링크·중복 쌍·<b>판의 노드에 없는 MDC</b> 를 가리키는
     * 링크는 거부한다 — 노드 절이 진실이고 링크는 그 위에 얹히는 축이라, 없는 MDC 로의 링크는 판 파일 오타다.
     * 데이터 전용 MDC(노드 없이 {@code dataMdcId} 로만 가리키는 사이트)가 필요한 링크 판이 들어오면 {@code mdcs:} 선언 절을
     * 두고 이 검사를 "노드 MDC ∪ 선언 MDC" 로 넓힌다 — 그때까지는 좁게 둔다.
     *
     * <p>쌍은 원장 규칙({@link MdcLink.Pair#of})으로 정렬해 담는다 — 파일에 {@code b,a} 로 적어도 {@code a,b} 와 같은 레코드다.
     * {@code latencyMs} 는 원장과 같이 왕복 지연이고, {@code bandwidthMbps} 는 원장과 같이 저장만 하고 점수에는 쓰지 않는다.</p>
     */
    @SuppressWarnings("unchecked")
    static List<Link> parseLinks(Object rawLinks, Set<String> nodeMdcIds, String location) {
        if (rawLinks == null) return List.of();
        if (!(rawLinks instanceof List<?> list)) throw new IllegalArgumentException("links 는 목록이어야 합니다: " + location);
        List<Link> links = new ArrayList<>();
        LinkedHashSet<String> pairs = new LinkedHashSet<>();
        for (Object o : list) {
            if (!(o instanceof Map<?, ?>)) throw new IllegalArgumentException("links 항목은 맵이어야 합니다: " + location + " → " + o);
            Map<String, Object> l = (Map<String, Object>) o;
            String a = str(l.get("mdcA"), null);
            String b = str(l.get("mdcB"), null);
            if (a == null || b == null || a.isBlank() || b.isBlank()) throw new IllegalArgumentException("links 항목에 mdcA·mdcB 가 없습니다: " + location);
            if (a.equals(b)) throw new IllegalArgumentException("같은 MDC 사이에는 링크를 둘 수 없습니다: " + a);
            if (!nodeMdcIds.contains(a) || !nodeMdcIds.contains(b)) {
                throw new IllegalArgumentException("links 가 판의 노드에 없는 MDC 를 가리킵니다: " + a + "-" + b + " (노드 MDC: " + nodeMdcIds
                        + "; 데이터 전용 MDC 는 아직 지원하지 않는다 — 필요하면 mdcs: 선언 절을 먼저 도입한다)");
            }
            MdcLink.Pair pair = MdcLink.Pair.of(a, b);
            if (!pairs.add(pair.a() + "|" + pair.b())) throw new IllegalArgumentException("links 쌍 중복: " + a + "-" + b);
            if (l.get("latencyMs") == null) throw new IllegalArgumentException("links 항목에 latencyMs 가 없습니다: " + a + "-" + b);
            double latency = num(l.get("latencyMs"), 0).doubleValue();
            if (latency < 0) throw new IllegalArgumentException("latencyMs 는 0 이상이어야 합니다: " + a + "-" + b);
            MdcLink.LinkStatus status;
            try {
                status = MdcLink.LinkStatus.valueOf(str(l.get("status"), "UP").trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("links status 는 " + java.util.Arrays.toString(MdcLink.LinkStatus.values()) + " 중 하나: " + a + "-" + b + " = " + l.get("status"));
            }
            Double bandwidth = l.get("bandwidthMbps") == null ? null : num(l.get("bandwidthMbps"), 0).doubleValue();
            if (bandwidth != null && !(bandwidth > 0)) throw new IllegalArgumentException("bandwidthMbps 는 0 보다 커야 합니다: " + a + "-" + b + " = " + bandwidth);
            Double loss = l.get("packetLossPercent") == null ? null : num(l.get("packetLossPercent"), 0).doubleValue();
            if (loss != null && (loss < 0 || loss > 100)) throw new IllegalArgumentException("packetLossPercent 는 0~100 이어야 합니다: " + a + "-" + b + " = " + loss);
            links.add(new Link(pair.a(), pair.b(), latency, bandwidth, loss, status));
        }
        return links;
    }

    private static String str(Object v, String dflt) { return v == null ? dflt : String.valueOf(v); }
    private static Number num(Object v, Number dflt) {
        if (v == null) return dflt;
        if (v instanceof Number n) return n;
        try { return Double.parseDouble(String.valueOf(v)); } catch (NumberFormatException e) { throw new IllegalArgumentException("숫자가 아닙니다: " + v); }
    }
    private static boolean bool(Object v, boolean dflt) { return v == null ? dflt : Boolean.parseBoolean(String.valueOf(v)); }
    private static double pct(Object v, double dflt, String nodeId) {
        double d = num(v, dflt).doubleValue();
        if (d < 0 || d > 100) throw new IllegalArgumentException("사용률은 0~100 이어야 합니다: " + nodeId + " = " + d);
        return d;
    }

    // ── 조회 ──────────────────────────────────────────────────

    public String name() { return name; }
    public String job() { return job; }

    /**
     * 파동의 주기(분). 한 주기를 훑는 계산은 이 값을 알아야 창을 정할 수 있다 —
     * 60 분이라고 가정하면 파일이 바뀌는 순간 조용히 같은 위상만 반복해서 훑게 된다.
     */
    public int periodMinutes() { return periodMinutes; }

    /**
     * 전력 생성식의 지수 β. 기본 1.0(선형) — 파일에 적지 않은 클러스터는 예전 그대로다.
     * 1 보다 크면 저부하에서 전력이 선형보다 낮은 <b>볼록</b> 판이 되고, 1 보다 작으면 오목 판이 된다.
     */
    public double powerExponent() { return powerExponent; }
    public List<Node> nodes() { return nodes; }
    /** 판이 선언한 링크. 지금 등록된 판은 전부 빈 목록이다. */
    public List<Link> links() { return links; }
    public int mdcCount() { return (int) nodes.stream().map(Node::mdcId).distinct().count(); }
    public Optional<Node> node(String nodeId) { return nodes.stream().filter(n -> n.nodeId().equals(nodeId)).findFirst(); }

    // ── 시각의 함수 ──────────────────────────────────────────

    /** 노드별 위상 — 식별자 해시로 정해져 파일이 같으면 늘 같다. */
    double phase(Node node) {
        long h = seed;
        for (char c : node.nodeId().toCharArray()) h = h * 31 + c;
        return (Math.floorMod(h, 1000) / 1000.0) * 2 * Math.PI;
    }

    /** 노드별 진폭 — 파일에 적었으면 그 값, 아니면 클러스터 공통. 유휴 노드를 진짜 유휴로 두려면 작게 적는다. */
    double swingOf(Node node) { return node.swing() != null ? node.swing() : swing; }

    /** 기준값을 중심으로 ±swing 만큼 흔들린 사용률(0~100 로 자름). */
    public double cpuUsage(Node node, long epochSeconds) {
        return clamp(node.cpuUsage() + swingOf(node) * wave(node, epochSeconds), 0, 100);
    }

    public double memoryUsage(Node node, long epochSeconds) {
        return clamp(node.memoryUsage() + swingOf(node) * 0.3 * wave(node, epochSeconds), 0, 100);
    }

    public double diskUsage(Node node, long epochSeconds) {
        return clamp(node.diskUsage() + 0.5 * wave(node, epochSeconds), 0, 100);   // 디스크는 거의 안 움직인다
    }

    /**
     * 전력 = 유휴 + (정격 − 유휴) × (CPU 사용률/100)^β. IPMI 없는 노드는 호출자가 묻지 않는다.
     *
     * <p>β=1.0 이면 <b>예전 식을 글자 그대로</b> 탄다. {@code Math.pow(x, 1.0) == x} 는 참이지만
     * 예전 식은 좌결합 {@code ((정격−유휴) × cpu) / 100} 이고 일반식은 {@code (정격−유휴) × (cpu/100)^β} 라
     * 결합 순서가 달라 마지막 비트가 어긋난다 — 지수를 적지 않은 판·운영 모의 모드의 출력을
     * 비트까지 그대로 두려고 분기를 남긴다.</p>
     */
    public double powerWatts(Node node, long epochSeconds) {
        double dyn = node.capacityWatts() - node.idleWatts();
        double cpu = cpuUsage(node, epochSeconds);
        if (powerExponent == 1.0) return node.idleWatts() + dyn * cpu / 100.0;   // 좌결합 원형 — 괄호를 넣으면 비트 동일이 깨진다
        return node.idleWatts() + dyn * Math.pow(cpu / 100.0, powerExponent);
    }

    /** 온도 = 기준 + CPU 사용률의 0.3 배 (100% 이면 +30°C). */
    public double temperature(Node node, long epochSeconds) {
        return node.baseTemperature() + 0.3 * cpuUsage(node, epochSeconds);
    }

    public double networkRx(Node node, long epochSeconds) { return Math.max(0, node.networkRxBps() * (1 + 0.5 * wave(node, epochSeconds))); }
    public double networkTx(Node node, long epochSeconds) { return Math.max(0, node.networkTxBps() * (1 + 0.5 * wave(node, epochSeconds))); }

    private double wave(Node node, long epochSeconds) {
        double periodSeconds = periodMinutes * 60.0;
        return Math.sin(2 * Math.PI * (epochSeconds % (long) periodSeconds) / periodSeconds + phase(node));
    }

    private static double clamp(double v, double lo, double hi) { return Math.max(lo, Math.min(hi, v)); }
}
