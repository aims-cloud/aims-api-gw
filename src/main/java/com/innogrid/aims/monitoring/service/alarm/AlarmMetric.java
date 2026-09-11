package com.innogrid.aims.monitoring.service.alarm;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import com.innogrid.aims.monitoring.dto.alarm.alarmPolicy.AlarmMetricCatalogResponse;
import com.innogrid.aims.monitoring.dto.alarm.alarmPolicy.AlarmResourceType;
import com.innogrid.aims.monitoring.service.resourceexplorer.tree.MetricSelector;

/**
 * 알람 메트릭 카탈로그 — 정책 마법사 "조건 설정"의 유형/메트릭 드롭다운과 평가기의 PromQL 을 한 곳에서 정의한다 (docs/09 C.2).
 *
 * <ul>
 *   <li>코드값(enum 이름)이 정책 원장에 저장되는 값이고, 표시명·단위는 기준 문자열({@code CPU Used > 80%})과
 *       관측값 표기({@code 92%})에 그대로 쓴다.</li>
 *   <li>PromQL 은 자원 탐색기 노드 패널({@code HostDetailMetricsQueryService}, {@code HostCategoryMetricsQueryService})의
 *       표준식과 같은 식이다. 화면 수치와 알람 판정이 같은 식에서 나오도록 여기 식을 바꾸면 패널 식도 같이 본다.</li>
 *   <li>{@link #promql(String, String, String)} 은 그룹 라벨·라벨 매처·rate 창을 받아 한 자원({@code instance="…"})이든
 *       전체 자원(매처 없이 {@code instance, mdc_id} 로 집계)이든 같은 템플릿으로 만든다.</li>
 *   <li>초안 규칙 이름 4종({@code cpu_usage} 등)은 별칭으로 받는다(구 alarm_rules 이관·외부 요청 호환용).</li>
 * </ul>
 */
public enum AlarmMetric {

    CPU_USED_PERCENT(Type.CPU, "CPU Used", "%", Set.of(AlarmResourceType.NODE, AlarmResourceType.VM), Set.of("cpu_usage")) {
        @Override
        public String promql(String groupBy, String matchers, String window) {
            return "100 - (avg by (" + groupBy + ") (rate(node_cpu_seconds_total"
                    + braces("mode=\"idle\"", matchers) + "[" + window + "])) * 100)";
        }
    },

    MEMORY_USED_PERCENT(Type.MEMORY, "Memory Used", "%", Set.of(AlarmResourceType.NODE, AlarmResourceType.VM), Set.of("mem_usage")) {
        @Override
        public String promql(String groupBy, String matchers, String window) {
            return "(1 - avg by (" + groupBy + ") (node_memory_MemAvailable_bytes" + braces(null, matchers)
                    + " / node_memory_MemTotal_bytes" + braces(null, matchers) + ")) * 100";
        }
    },

    MEMORY_AVAILABLE_GB(Type.MEMORY, "Memory Available", "GB", Set.of(AlarmResourceType.NODE, AlarmResourceType.VM), Set.of()) {
        @Override
        public String promql(String groupBy, String matchers, String window) {
            return "avg by (" + groupBy + ") (node_memory_MemAvailable_bytes" + braces(null, matchers) + ") / " + GB;
        }
    },

    FS_USED_PERCENT(Type.FILE_SYSTEM, "FS Used", "%", Set.of(AlarmResourceType.NODE, AlarmResourceType.VM), Set.of("disk_usage")) {
        @Override
        public String promql(String groupBy, String matchers, String window) {
            return "(1 - sum by (" + groupBy + ") (node_filesystem_avail_bytes" + braces(FS_FILTER, matchers)
                    + ") / sum by (" + groupBy + ") (node_filesystem_size_bytes" + braces(FS_FILTER, matchers) + ")) * 100";
        }
    },

    INODE_USED_PERCENT(Type.FILE_SYSTEM, "Inode Used", "%", Set.of(AlarmResourceType.NODE, AlarmResourceType.VM), Set.of()) {
        @Override
        public String promql(String groupBy, String matchers, String window) {
            return "(1 - sum by (" + groupBy + ") (node_filesystem_files_free" + braces(FS_FILTER, matchers)
                    + ") / sum by (" + groupBy + ") (node_filesystem_files" + braces(FS_FILTER, matchers) + ")) * 100";
        }
    },

    GPU_UTIL(Type.GPU, "GPU Util", "%", Set.of(AlarmResourceType.NODE, AlarmResourceType.GPU), Set.of()) {
        @Override
        public String promql(String groupBy, String matchers, String window) {
            return "avg by (" + groupBy + ") (DCGM_FI_DEV_GPU_UTIL" + braces(null, matchers) + ")";
        }
    },

    GPU_MEMORY_USED_PERCENT(Type.GPU, "GPU Mem", "%", Set.of(AlarmResourceType.NODE, AlarmResourceType.GPU), Set.of()) {
        @Override
        public String promql(String groupBy, String matchers, String window) {
            String used = "sum by (" + groupBy + ") (DCGM_FI_DEV_FB_USED" + braces(null, matchers) + ")";
            String free = "sum by (" + groupBy + ") (DCGM_FI_DEV_FB_FREE" + braces(null, matchers) + ")";
            return used + " / (" + used + " + " + free + ") * 100";
        }
    },

    GPU_TEMP(Type.GPU, "GPU Temperature", "°C", Set.of(AlarmResourceType.NODE, AlarmResourceType.GPU), Set.of("gpu_temperature")) {
        @Override
        public String promql(String groupBy, String matchers, String window) {
            return "max by (" + groupBy + ") (DCGM_FI_DEV_GPU_TEMP" + braces(null, matchers) + ")";
        }
    };

    /** 마법사 "유형" 드롭다운 — label 은 프론트 옵션 값과 동일 문자열 */
    public enum Type {
        CPU("CPU"), MEMORY("Memory"), FILE_SYSTEM("File System"), GPU("GPU");

        private final String label;

        Type(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    /** 자원 탐색기 File System 패널과 같은 파일시스템 제외 필터 */
    static final String FS_FILTER = "fstype!~\"tmpfs|overlay\"";
    static final long GB = 1L << 30;

    private final Type type;
    private final String displayName;
    private final String unit;
    private final Set<AlarmResourceType> resourceTypes;
    private final Set<String> aliases;

    AlarmMetric(Type type, String displayName, String unit, Set<AlarmResourceType> resourceTypes, Set<String> aliases) {
        this.type = type;
        this.displayName = displayName;
        this.unit = unit;
        this.resourceTypes = resourceTypes;
        this.aliases = aliases;
    }

    public Type type() {
        return type;
    }

    public String displayName() {
        return displayName;
    }

    public String unit() {
        return unit;
    }

    /** 이 메트릭을 해당 자원 유형 코드({@link AlarmResourceType})의 정책에 쓸 수 있는가. 모르는 유형·null 은 false */
    public boolean appliesTo(String resourceType) {
        return AlarmResourceType.fromCode(resourceType).map(resourceTypes::contains).orElse(false);
    }

    /**
     * PromQL 템플릿.
     *
     * @param groupBy  집계 라벨 — 한 자원이면 {@code instance}, 전체 평가면 {@code instance, mdc_id} 처럼 자원을 구분하는 라벨
     * @param matchers 라벨 매처(중괄호 없이, 예: {@code instance="10.0.0.1:9100"}). null/빈 문자열이면 전체 자원
     * @param window   rate 창(예: {@code 5m}). rate 를 쓰지 않는 메트릭은 무시
     */
    public abstract String promql(String groupBy, String matchers, String window);

    /** 자원 하나의 현재값 — 자원 탐색기 선택자를 그대로 받는다 */
    public String promql(MetricSelector selector, String window) {
        return promql("instance", selector.matchers(), window);
    }

    /** 기준 문자열 — {@code CPU Used > 80%} (마법사 요약·이력 criterion 스냅샷 공용) */
    public String criterion(String operatorSymbol, double threshold) {
        String value = threshold == Math.rint(threshold) ? String.valueOf((long) threshold) : String.valueOf(threshold);
        return displayName + " " + operatorSymbol + " " + value + unit;
    }

    /** 코드값 또는 초안 별칭(대소문자 무관)으로 찾는다 */
    public static Optional<AlarmMetric> resolve(String codeOrAlias) {
        if (codeOrAlias == null || codeOrAlias.isBlank()) return Optional.empty();
        String key = codeOrAlias.trim();
        return Arrays.stream(values())
                .filter(m -> m.name().equalsIgnoreCase(key) || m.aliases.contains(key.toLowerCase(Locale.ROOT)))
                .findFirst();
    }

    /**
     * 마법사 카탈로그 — 유형 순서(CPU, Memory, File System, GPU)로 묶고, 자원 유형이 주어지면 그 유형에 쓸 수 있는 메트릭만.
     * 메트릭이 하나도 없는 유형은 빠진다.
     */
    public static List<AlarmMetricCatalogResponse> catalog(String resourceType) {
        Map<Type, List<AlarmMetricCatalogResponse.MetricOption>> byType = new LinkedHashMap<>();
        for (Type t : Type.values()) byType.put(t, new java.util.ArrayList<>());
        for (AlarmMetric m : values()) {
            if (resourceType != null && !m.appliesTo(resourceType)) continue;
            byType.get(m.type).add(new AlarmMetricCatalogResponse.MetricOption(m.name(), m.displayName, m.unit));
        }
        return byType.entrySet().stream()
                .filter(e -> !e.getValue().isEmpty())
                .map(e -> new AlarmMetricCatalogResponse(e.getKey().label(), e.getValue()))
                .toList();
    }

    /** {@code {extra,matchers}} — 둘 중 비어 있는 쪽은 생략, 둘 다 없으면 빈 문자열 */
    static String braces(String extra, String matchers) {
        boolean hasExtra = extra != null && !extra.isBlank();
        boolean hasMatchers = matchers != null && !matchers.isBlank();
        if (!hasExtra && !hasMatchers) return "";
        if (hasExtra && hasMatchers) return "{" + extra + "," + matchers + "}";
        return "{" + (hasExtra ? extra : matchers) + "}";
    }
}
