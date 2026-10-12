package com.innogrid.aims.dashboard.service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiConsumer;
import com.innogrid.aims.dashboard.GpuDashboardProperties;
import com.innogrid.aims.dashboard.model.GpuWindow;
import com.innogrid.aims.monitoring.VictoriaMetricsClient;
import org.springframework.stereotype.Component;

/**
 * Thanos 의 DCGM 시계열을 조회 기간 단위로 읽어 GPU 할당 단위 목록으로 만든다 (Confluence 2320072739 §3, 2단계).
 *
 * <ul>
 *   <li>사용률·VRAM·온도·전력은 창 평균({@code avg_over_time}), Peak VRAM 은 창 최대({@code max_over_time}). 창 끝 시각에 평가한다.</li>
 *   <li>MIG: dcgm_exporter 가 인스턴스별로 {@code GPU_I_ID}·{@code GPU_I_PROFILE} 라벨을 붙인다 → 인스턴스 1개 = 단위 1개.
 *       MIG 인스턴스에는 {@code DCGM_FI_DEV_GPU_UTIL} 이 없어 {@code DCGM_FI_PROF_GR_ENGINE_ACTIVE × 100} 을 쓴다.
 *       같은 GPU 의 부모 시계열(GPU_I_ID 없음)은 VRAM 이중 집계를 막으려고 버린다. 테스트베드 MIG 구성으로 아직 확인하지 못했다.</li>
 *   <li>끊긴 GPU: 창 끝 기준 최근 하루 안에 보였으나 창에 표본이 없으면 collecting=false 로 남긴다.</li>
 * </ul>
 * 같은 창의 결과는 {@code aims.gpu-dashboard.cache-ttl-seconds} 동안 재사용한다(카드·차트 API 가 같은 갱신 주기에 여러 번 부른다).
 */
@Component
public class DcgmSnapshotLoader {

    static final String UTIL = "avg_over_time(DCGM_FI_DEV_GPU_UTIL%1$s) or (avg_over_time(DCGM_FI_PROF_GR_ENGINE_ACTIVE{GPU_I_ID!=\"\"}%1$s) * 100)";
    static final String FB_USED = "avg_over_time(DCGM_FI_DEV_FB_USED%s)";
    static final String FB_FREE = "avg_over_time(DCGM_FI_DEV_FB_FREE%s)";
    static final String FB_PEAK = "max_over_time(DCGM_FI_DEV_FB_USED%s)";
    static final String TEMP = "avg_over_time(DCGM_FI_DEV_GPU_TEMP%s)";
    static final String POWER = "avg_over_time(DCGM_FI_DEV_POWER_USAGE%s)";
    static final String SEEN = "last_over_time(DCGM_FI_DEV_FB_FREE[1d])";
    static final int MAX_CACHED_WINDOWS = 16;

    /** GPU 할당 단위 1개의 DCGM 값. 값이 없으면 null */
    public record DcgmUnit(String id, String name, String instance, String hostname, String gpuIndex, String uuid,
                           String modelName, boolean mig, Double utilization, Double fbUsedMb, Double fbFreeMb,
                           Double peakFbUsedMb, Double temperatureC, Double powerWatts, boolean collecting) {}

    /** 한 창의 조회 결과 — 단위 목록과, 창 안에 표본이 있는 instance 집합 */
    public record DcgmSnapshot(List<DcgmUnit> units, Set<String> collectingInstances, Instant loadedAt) {}

    private final VictoriaMetricsClient metrics;
    private final GpuDashboardProperties properties;
    private final Map<String, DcgmSnapshot> cache = new LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, DcgmSnapshot> eldest) {
            return size() > MAX_CACHED_WINDOWS;
        }
    };
    private Clock clock = Clock.systemUTC();

    public DcgmSnapshotLoader(VictoriaMetricsClient metrics, GpuDashboardProperties properties) {
        this.metrics = metrics;
        this.properties = properties;
    }

    /** 테스트용 — 캐시 수명 판정 시계 */
    void setClock(Clock clock) {
        this.clock = clock;
    }

    public DcgmSnapshot load(GpuWindow window) {
        Instant now = clock.instant();
        long ttl = properties.getCacheTtlSeconds();
        String key = window.cacheKey();
        synchronized (cache) {
            DcgmSnapshot hit = cache.get(key);
            if (hit != null && ttl > 0 && Duration.between(hit.loadedAt(), now).getSeconds() < ttl) {
                return hit;
            }
        }
        DcgmSnapshot loaded = query(window, now);
        if (ttl > 0) {
            synchronized (cache) {
                cache.put(key, loaded);
            }
        }
        return loaded;
    }

    private DcgmSnapshot query(GpuWindow window, Instant now) {
        String range = window.range();
        Instant at = window.to();
        Map<String, Builder> units = new LinkedHashMap<>();
        merge(units, String.format(UTIL, range), at, (b, v) -> b.utilization = v);
        merge(units, String.format(FB_USED, range), at, (b, v) -> b.fbUsed = v);
        merge(units, String.format(FB_FREE, range), at, (b, v) -> b.fbFree = v);
        merge(units, String.format(FB_PEAK, range), at, (b, v) -> b.peak = v);
        merge(units, String.format(TEMP, range), at, (b, v) -> b.temperature = v);
        merge(units, String.format(POWER, range), at, (b, v) -> b.power = v);
        merge(units, SEEN, at, (b, v) -> { });

        // MIG 인스턴스가 있는 GPU 의 부모 단위는 버린다 (VRAM 이중 집계 방지)
        Set<String> migParents = new HashSet<>();
        units.values().stream().filter(b -> b.mig).forEach(b -> migParents.add(b.baseId));
        List<DcgmUnit> result = units.values().stream()
                .filter(b -> b.mig || !migParents.contains(b.baseId))
                .map(Builder::build)
                .toList();
        Set<String> collecting = new LinkedHashSet<>();
        result.stream().filter(DcgmUnit::collecting).map(DcgmUnit::instance).filter(i -> i != null).forEach(collecting::add);
        return new DcgmSnapshot(result, Set.copyOf(collecting), now);
    }

    @SuppressWarnings("unchecked")
    private void merge(Map<String, Builder> units, String promQl, Instant at, BiConsumer<Builder, Double> setter) {
        for (Map<String, Object> row : metrics.queryResultsAt(promQl, at)) {
            Map<String, String> labels = (Map<String, String>) row.getOrDefault("metric", Map.of());
            List<Object> value = (List<Object>) row.get("value");
            if (value == null || value.size() < 2) {
                continue;
            }
            double parsed;
            try {
                parsed = Double.parseDouble(value.get(1).toString());
            } catch (NumberFormatException e) {
                continue;
            }
            if (Double.isNaN(parsed)) {
                continue;
            }
            Builder builder = units.computeIfAbsent(unitId(labels), id -> new Builder(id, labels));
            setter.accept(builder, parsed);
        }
    }

    /** 단위 id — 물리 GPU 는 UUID(없으면 Hostname:gpu), MIG 는 그 뒤에 /GPU_I_ID. 시계열 차트도 같은 id 를 쓴다 */
    public static String unitId(Map<String, String> labels) {
        String base = baseId(labels);
        String instanceId = labels.get("GPU_I_ID");
        return instanceId == null || instanceId.isBlank() ? base : base + "/" + instanceId;
    }

    /** 화면 이름 — "{Hostname} / gpu {gpu}", MIG 면 " / MIG {GPU_I_PROFILE}" */
    public static String unitName(Map<String, String> labels) {
        String host = labels.getOrDefault("Hostname", labels.getOrDefault("instance", "?"));
        String name = host + " / gpu " + labels.getOrDefault("gpu", "?");
        String instanceId = labels.get("GPU_I_ID");
        if (instanceId != null && !instanceId.isBlank()) {
            name += " / MIG " + labels.getOrDefault("GPU_I_PROFILE", instanceId);
        }
        return name;
    }

    private static String baseId(Map<String, String> labels) {
        String uuid = labels.get("UUID");
        return uuid != null && !uuid.isBlank() ? uuid : labels.getOrDefault("Hostname", "unknown") + ":" + labels.getOrDefault("gpu", "0");
    }

    private static final class Builder {
        final String id;
        final String baseId;
        final Map<String, String> labels;
        final boolean mig;
        Double utilization;
        Double fbUsed;
        Double fbFree;
        Double peak;
        Double temperature;
        Double power;

        Builder(String id, Map<String, String> labels) {
            this.id = id;
            this.baseId = baseId(labels);
            this.labels = labels;
            String instanceId = labels.get("GPU_I_ID");
            this.mig = instanceId != null && !instanceId.isBlank();
        }

        DcgmUnit build() {
            boolean collecting = utilization != null || fbUsed != null;
            return new DcgmUnit(id, unitName(labels), labels.get("instance"), labels.get("Hostname"), labels.get("gpu"),
                    labels.get("UUID"), labels.get("modelName"), mig, utilization, fbUsed, fbFree, peak, temperature,
                    power, collecting);
        }
    }
}
