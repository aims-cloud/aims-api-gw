package com.innogrid.aims.scheduler.benchmark;

import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.innogrid.aims.scheduler.entity.Workload;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 벤치마크 워크로드 분포 로딩·표본 추출·배치 순서.
 *
 * <p>스프링을 띄우지 않는다 — {@link BenchmarkWorkloads} 가 만드는 워크로드는 저장하지 않는
 * detached 엔티티라 DB 도 컨텍스트도 필요 없다.</p>
 */
class BenchmarkWorkloadsTest {

    private static final String HEAVYTAIL = "classpath:scheduler/benchmark-workloads-heavytail.yml";
    private static final String UNIFORM = "classpath:scheduler/benchmark-workloads-uniform.yml";

    @DisplayName("같은 목표면 같은 표본이다")
    @Test
    void same_target_gives_the_same_sample() {
        BenchmarkWorkloads workloads = BenchmarkWorkloads.load(HEAVYTAIL);

        List<Workload> first = workloads.sample(150);
        List<Workload> second = workloads.sample(150);

        assertEquals(first.size(), second.size());
        for (int i = 0; i < first.size(); i++) {
            assertEquals(first.get(i).getName(), second.get(i).getName());
            assertEquals(first.get(i).getRequiredCpu(), second.get(i).getRequiredCpu());
            assertEquals(first.get(i).getRequiredGpu(), second.get(i).getRequiredGpu());
            assertEquals(first.get(i).getPriority(), second.get(i).getPriority());
        }
    }

    @DisplayName("부하율이 오름차순이면 표본이 접두부로 겹친다")
    @Test
    void samples_nest_as_prefixes_across_load_ratios() {
        BenchmarkWorkloads workloads = BenchmarkWorkloads.load(HEAVYTAIL);

        List<List<Workload>> samples = BenchmarkRules.LOAD_RATIOS.stream()
                .map(ratio -> workloads.sample((double) ratio))
                .toList();

        for (int i = 1; i < samples.size(); i++) {
            List<Workload> prev = samples.get(i - 1);
            List<Workload> next = samples.get(i);
            assertTrue(next.size() >= prev.size(),
                    "더 큰 목표의 표본이 더 작아질 수 없다: " + BenchmarkRules.LOAD_RATIOS.get(i - 1)
                            + "% -> " + BenchmarkRules.LOAD_RATIOS.get(i) + "%");
            for (int j = 0; j < prev.size(); j++) {
                assertEquals(prev.get(j).getName(), next.get(j).getName(), "인덱스 " + j + " 에서 접두부가 어긋난다");
                assertEquals(prev.get(j).getRequiredCpu(), next.get(j).getRequiredCpu());
                assertEquals(prev.get(j).getRequiredGpu(), next.get(j).getRequiredGpu());
                assertEquals(prev.get(j).getPriority(), next.get(j).getPriority());
            }
        }
    }

    @DisplayName("목표가 커지면 요구 코어 합이 엄격히 증가한다")
    @Test
    void requested_cores_increase_strictly() {
        BenchmarkWorkloads workloads = BenchmarkWorkloads.load(HEAVYTAIL);

        List<Integer> sums = BenchmarkRules.LOAD_RATIOS.stream()
                .map(ratio -> workloads.sample((double) ratio).stream()
                        .mapToInt(Workload::getRequiredCpu).sum())
                .toList();

        for (int i = 1; i < sums.size(); i++) {
            assertTrue(sums.get(i) > sums.get(i - 1),
                    "부하율 " + BenchmarkRules.LOAD_RATIOS.get(i) + "% 의 요구 코어 합이 "
                            + BenchmarkRules.LOAD_RATIOS.get(i - 1) + "% 보다 커야 한다: " + sums);
        }
    }

    @DisplayName("메모리는 코어 × 선언값이다")
    @Test
    void memory_is_cores_times_declared_per_core() {
        BenchmarkWorkloads workloads = BenchmarkWorkloads.load(HEAVYTAIL);

        List<Workload> sample = workloads.sample(300);
        assertFalse(sample.isEmpty());
        for (Workload w : sample) {
            assertEquals(w.getRequiredCpu() * workloads.memoryPerCore(), w.getRequiredMemoryMb(),
                    w.getName() + ": 메모리가 코어 × " + workloads.memoryPerCore() + " 이 아니다");
        }
    }

    @DisplayName("선언한 분포가 구성비를 만든다")
    @Test
    void distribution_shapes_the_mix() {
        BenchmarkWorkloads heavytail = BenchmarkWorkloads.load(HEAVYTAIL);
        BenchmarkWorkloads uniform = BenchmarkWorkloads.load(UNIFORM);

        double heavytailOneCoreShare = oneCoreShare(heavytail.sample(3000));
        double uniformOneCoreShare = oneCoreShare(uniform.sample(3000));

        assertTrue(heavytailOneCoreShare > uniformOneCoreShare + 0.1,
                "heavytail(소형 weight 60) 의 1코어 비중이 uniform(소형 weight 25) 보다 뚜렷이 높아야 한다: "
                        + "heavytail=" + heavytailOneCoreShare + ", uniform=" + uniformOneCoreShare);
    }

    private static double oneCoreShare(List<Workload> sample) {
        long oneCoreCount = sample.stream().filter(w -> w.getRequiredCpu() == 1).count();
        return (double) oneCoreCount / sample.size();
    }

    @DisplayName("워크로드 이름은 유일하다")
    @Test
    void workload_names_are_unique() {
        BenchmarkWorkloads workloads = BenchmarkWorkloads.load(HEAVYTAIL);

        List<Workload> sample = workloads.sample(500);
        Set<String> names = sample.stream().map(Workload::getName).collect(Collectors.toSet());

        assertEquals(sample.size(), names.size(), "표본 안에 이름이 중복된 워크로드가 있다");
    }

    @DisplayName("큰 표본에는 GPU 워크로드가 들어 있다")
    @Test
    void gpu_class_appears_in_a_large_sample() {
        BenchmarkWorkloads workloads = BenchmarkWorkloads.load(HEAVYTAIL);

        List<Workload> sample = workloads.sample(3000);

        assertTrue(sample.stream().anyMatch(w -> w.getRequiredGpu() > 0),
                "GPU 계층의 weight(5%) 로 볼 때 이 정도 표본이면 최소 1건은 나와야 한다");
    }

    @DisplayName("배치 순서는 우선순위 다음 크기 내림차순이다")
    @Test
    void placement_order_sorts_by_priority_then_size_desc() {
        Workload low = new Workload("low", 2, 8192);
        low.setPriority(Workload.WorkloadPriority.LOW);
        Workload normal = new Workload("normal", 8, 32768);
        normal.setPriority(Workload.WorkloadPriority.NORMAL);
        Workload highSmall = new Workload("high-small", 1, 4096);
        highSmall.setPriority(Workload.WorkloadPriority.HIGH);
        Workload highBig = new Workload("high-big", 4, 16384);
        highBig.setPriority(Workload.WorkloadPriority.HIGH);

        List<Workload> ordered = BenchmarkWorkloads.inPlacementOrder(List.of(low, normal, highSmall, highBig));

        assertEquals(List.of("high-big", "high-small", "normal", "low"),
                ordered.stream().map(Workload::getName).toList(),
                "HIGH 가 먼저, 그 안에서는 CPU 큰 것부터(4 -> 1), 그다음 NORMAL, 그다음 LOW");
    }

    @DisplayName("배치 순서 정렬이 표본 집합을 바꾸지 않는다")
    @Test
    void placement_order_does_not_change_the_sample_set() {
        BenchmarkWorkloads workloads = BenchmarkWorkloads.load(HEAVYTAIL);

        List<Workload> sample = workloads.sample(500);
        List<Workload> ordered = BenchmarkWorkloads.inPlacementOrder(sample);

        assertEquals(sample.size(), ordered.size());

        // 지역 변수로 받는다. 스트림 수집 결과를 인자 자리에 바로 두면 원소 타입이 추론되기 전에
        // assertEquals 오버로드가 골라져 Byte/Short 판까지 후보가 되어 모호해진다.
        Set<String> before = sample.stream().map(Workload::getName)
                .collect(Collectors.toCollection(TreeSet::new));
        Set<String> after = ordered.stream().map(Workload::getName)
                .collect(Collectors.toCollection(TreeSet::new));

        assertEquals(before, after,
                "정렬 전후 이름 집합이 같아야 한다 — 정렬이 표본을 더하거나 빼면 안 된다");
    }

    @DisplayName("잘못된 파일은 조용히 통과하지 않는다")
    @Test
    void broken_file_fails_loudly() {
        assertThrows(IllegalArgumentException.class,
                () -> BenchmarkWorkloads.load("classpath:scheduler/does-not-exist.yml"));
    }

    @DisplayName("균일 분포 파일도 읽힌다")
    @Test
    void uniform_file_loads() {
        BenchmarkWorkloads uniform = BenchmarkWorkloads.load(UNIFORM);

        assertEquals("uniform-4class", uniform.name());
        assertEquals(4, uniform.classes().size());
        assertTrue(uniform.classes().stream().allMatch(c -> c.weight() == 25),
                "균일 분포는 계층마다 weight 가 25 로 같아야 한다");
    }
}
