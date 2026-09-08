package com.innogrid.aims.scheduler.metrics;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.core.io.Resource;
import org.yaml.snakeyaml.Yaml;

/**
 * 가상 클러스터 — 1세부 데이터가 없는 동안 스케줄러가 볼 노드들의 정의와, 시간에 따라 값을 만드는 결정적 함수.
 *
 * <p>YAML 한 장이 MDC·노드·기준값을 준다. 값은 <b>시각의 함수</b>라 같은 시각·같은 파일이면 언제 물어도 같다(재현 가능).
 * 사인 곡선(주기 {@code periodMinutes}) 위에 노드별 위상을 얹어 노드마다 다른 곡선을 만들고, 진폭은 {@code swing}
 * (사용률 %p)이다. 전력은 유휴 전력 + (정격 − 유휴) × CPU 사용률^{@code powerExponent}(기본 1 — 선형)로
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

    private final String name;
    private final String job;
    private final int periodMinutes;
    private final double swing;
    private final long seed;
    private final double powerExponent;
    private final List<Node> nodes;

    SyntheticCluster(String name, String job, int periodMinutes, double swing, long seed,
                     double powerExponent, List<Node> nodes) {
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
        Object rawNodes = map.get("nodes");
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
        return new SyntheticCluster(name, job, period, swing, seed, powerExponent, nodes);
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
     * 전력 = 유휴 + (정격 − 유휴) × 사용률^β. IPMI 없는 노드는 호출자가 묻지 않는다.
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
