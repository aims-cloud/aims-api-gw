package com.innogrid.aims.scheduler.benchmark;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Random;

import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.core.io.Resource;
import org.yaml.snakeyaml.Yaml;

import com.innogrid.aims.scheduler.entity.Workload;

/**
 * 배치 비교용 워크로드 분포 — 손으로 고른 목록이 아니라 파일에 선언된 분포에서 결정적으로 뽑는다.
 *
 * <p>DB 워크로드는 쓰지 않는다. {@code PlacementContextAssembler.applyReservations} 가 DB 워크로드를 예약으로
 * 얹기 때문에, DB 에서 가져온 워크로드를 다시 배치 대상으로 주면 같은 워크로드가 예약으로 한 번, 배치
 * 대상으로 또 한 번 계산된다. 그래서 이 클래스가 만드는 워크로드는 저장하지 않는 <b>detached 엔티티</b>다
 * — {@code getId()}·{@code getCreatedAt()} 은 늘 null 이다.</p>
 *
 * <p>YAML 로딩은 {@code SyntheticCluster.load} 와 같은 방식(snakeyaml + {@link DefaultResourceLoader}),
 * 같은 문체의 예외를 쓴다.</p>
 *
 * <p><b>집합을 정하는 일과 배치 순서를 정하는 일을 분리한다.</b> {@link #sample(double)} 은 뽑힌
 * 순서 그대로 목록을 돌려주고 정렬하지 않는다 — 표본을 만들 때부터 우선순위·크기로 정렬하면 30% 판이
 * "가장 크고 우선순위 높은 것만"이 되어 표본 자체가 편향된다. 정렬은 표본을 다 뽑은 뒤 {@link
 * #inPlacementOrder(List)} 가 한다.</p>
 */
public final class BenchmarkWorkloads {

    /** 분포 한 계층 — 가중치와 자원 요구. */
    public record WorkloadClass(String name, int weight, int cores, int gpus,
                                Workload.WorkloadPriority priority) {}

    private final String name;
    private final long seed;
    private final int memoryPerCore;
    private final int diskGb;
    private final List<WorkloadClass> classes;

    BenchmarkWorkloads(String name, long seed, int memoryPerCore, int diskGb, List<WorkloadClass> classes) {
        this.name = name;
        this.seed = seed;
        this.memoryPerCore = memoryPerCore;
        this.diskGb = diskGb;
        this.classes = List.copyOf(classes);
    }

    // ── 로딩 ──────────────────────────────────────────────────

    /** 위치 → 다이제스트 메모이즈. 캐시 적중 경로가 매 호출 파일을 다시 읽지 않게 — 판 다이제스트와 같은 방식. */
    private static final Map<String, String> DIGESTS = new java.util.concurrent.ConcurrentHashMap<>();

    /** 워크로드 분포 파일 내용의 다이제스트 — 판 파일과 같은 규칙({@link BenchmarkClusters#digestOfContent}). 캐시 키·증거 묶음이 쓴다. */
    public static String digestOf(String location) {
        return DIGESTS.computeIfAbsent(location, loc -> {
            Resource resource = new DefaultResourceLoader().getResource(loc);
            try (InputStream in = resource.getInputStream()) {
                return BenchmarkClusters.digestOfContent(in.readAllBytes());
            } catch (IOException e) {
                throw new IllegalArgumentException("워크로드 분포 파일을 읽을 수 없습니다: " + loc, e);
            }
        });
    }

    /** {@code classpath:} 또는 파일 경로에서 읽는다. 잘못된 파일은 {@link IllegalArgumentException}. */
    public static BenchmarkWorkloads load(String location) {
        Resource resource = new DefaultResourceLoader().getResource(location);
        try (InputStream in = resource.getInputStream()) {
            return parse(new Yaml().load(in), location);
        } catch (IOException e) {
            throw new IllegalArgumentException("워크로드 분포 파일을 읽을 수 없습니다: " + location, e);
        }
    }

    @SuppressWarnings("unchecked")
    static BenchmarkWorkloads parse(Object root, String location) {
        if (!(root instanceof Map<?, ?> m)) throw new IllegalArgumentException("워크로드 분포 파일이 비었습니다: " + location);
        Map<String, Object> map = (Map<String, Object>) m;
        String name = str(map.get("name"), "workloads");
        long seed = num(map.get("seed"), 1).longValue();
        int memoryPerCore = num(map.get("memoryPerCore"), 4096).intValue();
        int diskGb = num(map.get("diskGb"), 0).intValue();

        Object rawClasses = map.get("classes");
        if (!(rawClasses instanceof List<?> list) || list.isEmpty()) {
            throw new IllegalArgumentException("classes 가 비었습니다: " + location);
        }

        List<WorkloadClass> classes = new ArrayList<>();
        long weightSum = 0;
        for (Object o : list) {
            Map<String, Object> c = (Map<String, Object>) o;
            String className = str(c.get("name"), null);
            if (className == null || className.isBlank()) {
                throw new IllegalArgumentException("classes 항목에 name 이 없습니다: " + location);
            }
            int weight = num(c.get("weight"), 0).intValue();
            int cores = num(c.get("cores"), 0).intValue();
            if (cores <= 0) throw new IllegalArgumentException("cores 는 0 보다 커야 합니다: " + className);
            int gpus = num(c.get("gpus"), 0).intValue();
            Workload.WorkloadPriority priority = priority(str(c.get("priority"), "NORMAL"), className);
            classes.add(new WorkloadClass(className, weight, cores, gpus, priority));
            weightSum += weight;
        }
        if (weightSum <= 0) throw new IllegalArgumentException("weight 합이 0 이하입니다: " + location);

        return new BenchmarkWorkloads(name, seed, memoryPerCore, diskGb, classes);
    }

    private static Workload.WorkloadPriority priority(String v, String className) {
        try {
            return Workload.WorkloadPriority.valueOf(v.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("알 수 없는 priority: " + v + " (" + className + ")");
        }
    }

    private static String str(Object v, String dflt) { return v == null ? dflt : String.valueOf(v); }
    private static Number num(Object v, Number dflt) {
        if (v == null) return dflt;
        if (v instanceof Number n) return n;
        try { return Double.parseDouble(String.valueOf(v)); } catch (NumberFormatException e) { throw new IllegalArgumentException("숫자가 아닙니다: " + v); }
    }

    // ── 조회 ──────────────────────────────────────────────────

    public String name() { return name; }
    public long seed() { return seed; }
    public int memoryPerCore() { return memoryPerCore; }
    public int diskGb() { return diskGb; }
    public List<WorkloadClass> classes() { return classes; }

    // ── 표본 추출 ────────────────────────────────────────────

    /**
     * 목표 코어 수에 닿을 때까지 <b>표본 순서</b>로 뽑는다(정렬하지 않는다).
     *
     * <p>{@code new Random(seed)} 하나로 처음부터 순서대로 뽑는다 — 목표에 맞춰 가중치를 다시
     * 정규화하거나 미리 정렬하지 않는다. 그래서 같은 시드로 뽑는 이상, 작은 목표의 결과는 큰 목표의
     * 결과의 <b>접두부</b>가 된다(부하율이 오름차순이면 표본이 겹치는 성질은 여기서 나온다).</p>
     *
     * <p>누적 코어가 {@code targetCores} 를 넘는 순간 멈춘다 — 그 워크로드는 포함한다.</p>
     */
    public List<Workload> sample(double targetCores) {
        List<Workload> result = new ArrayList<>();
        if (targetCores <= 0) return result;

        Random random = new Random(seed);
        int totalWeight = classes.stream().mapToInt(WorkloadClass::weight).sum();
        long cumulativeCores = 0;
        int sequence = 0;
        do {
            WorkloadClass picked = pick(random, totalWeight);
            sequence++;
            Workload workload = new Workload("bm-" + sequence, picked.cores(), picked.cores() * memoryPerCore);
            workload.setRequiredGpu(picked.gpus());
            workload.setRequiredDiskGb(diskGb);
            workload.setPriority(picked.priority());
            result.add(workload);
            cumulativeCores += picked.cores();
        } while (cumulativeCores <= targetCores);

        return result;
    }

    /** 가중 추출 한 번. {@code totalWeight} 는 {@link #classes} 의 weight 합과 반드시 일치해야 한다. */
    private WorkloadClass pick(Random random, int totalWeight) {
        int r = random.nextInt(totalWeight);
        int acc = 0;
        for (WorkloadClass c : classes) {
            acc += c.weight();
            if (r < acc) return c;
        }
        return classes.get(classes.size() - 1);   // totalWeight 가 실제 weight 합과 같은 한 닿지 않는다
    }

    // ── 배치 순서 ────────────────────────────────────────────

    /**
     * 배치 순서 정렬 — 우선순위 → 요구 CPU → 요구 메모리 내림차순, 동률은 표본 순번.
     *
     * <p>표본의 워크로드는 저장하지 않는 detached 엔티티라 {@code getId()}·{@code getCreatedAt()} 이
     * null 이다. 그래서 별도의 표본 순번 비교자를 만들지 않는다 — {@link List#sort} 는 안정 정렬이라,
     * 우선순위·CPU·메모리가 모두 같은 워크로드는 입력 목록에 있던 순서(표본 순번) 그대로 남는다.</p>
     */
    public static List<Workload> inPlacementOrder(List<Workload> sampled) {
        List<Workload> ordered = new ArrayList<>(sampled);
        ordered.sort(Comparator.comparingInt((Workload w) -> w.getPriority().ordinal()).reversed()
                .thenComparing(Comparator.comparingInt(Workload::getRequiredCpu).reversed())
                .thenComparing(Comparator.comparingInt(Workload::getRequiredMemoryMb).reversed()));
        return ordered;
    }
}
