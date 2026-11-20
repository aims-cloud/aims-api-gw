package com.innogrid.aims.dashboard.service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import com.innogrid.aims.dashboard.GpuDashboardProperties;
import com.innogrid.aims.dashboard.model.GpuWindow;
import com.innogrid.aims.dashboard.service.DcgmSnapshotLoader.DcgmSnapshot;
import com.innogrid.aims.dashboard.service.DcgmSnapshotLoader.DcgmUnit;
import com.innogrid.aims.monitoring.VictoriaMetricsClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** DCGM 단위 구성 — MIG 인스턴스 분리·부모 제거, 수집 끊긴 GPU, NaN 무시, 창별 캐시와 수명. */
class DcgmSnapshotLoaderTest {

    VictoriaMetricsClient metrics = mock(VictoriaMetricsClient.class);
    GpuDashboardProperties properties = new GpuDashboardProperties();
    DcgmSnapshotLoader loader = new DcgmSnapshotLoader(metrics, properties);
    Instant now = Instant.parse("2026-10-06T03:00:00Z");
    Clock clock = Clock.fixed(now, ZoneOffset.UTC);

    static Map<String, Object> row(Map<String, String> labels, String value) {
        return Map.of("metric", labels, "value", List.of(1, value));
    }

    @BeforeEach void setup() {
        loader.setClock(clock);
        when(metrics.queryResultsAt(anyString(), any())).thenReturn(List.of());
    }

    @Test void migInstancesAreSeparateUnitsAndReplaceTheirParent() {
        Map<String, String> parent = Map.of("instance", "n:9400", "UUID", "GPU-m", "gpu", "0", "Hostname", "h");
        Map<String, String> mig1 = Map.of("instance", "n:9400", "UUID", "GPU-m", "gpu", "0", "Hostname", "h",
                "GPU_I_ID", "1", "GPU_I_PROFILE", "1g.10gb");
        Map<String, String> mig2 = Map.of("instance", "n:9400", "UUID", "GPU-m", "gpu", "0", "Hostname", "h",
                "GPU_I_ID", "2", "GPU_I_PROFILE", "3g.40gb");
        when(metrics.queryResultsAt(startsWith("avg_over_time(DCGM_FI_DEV_GPU_UTIL"), any()))
                .thenReturn(List.of(row(mig1, "40"), row(mig2, "70")));
        when(metrics.queryResultsAt(startsWith("avg_over_time(DCGM_FI_DEV_FB_USED"), any()))
                .thenReturn(List.of(row(parent, "20000"), row(mig1, "5000"), row(mig2, "15000")));

        DcgmSnapshot snapshot = loader.load(GpuWindow.of(null, null, clock));

        assertThat(snapshot.units()).extracting(DcgmUnit::id).containsExactly("GPU-m/1", "GPU-m/2");   // 부모 GPU-m 은 없다
        DcgmUnit first = snapshot.units().get(0);
        assertThat(first.mig()).isTrue();
        assertThat(first.name()).isEqualTo("h / gpu 0 / MIG 1g.10gb");
        assertThat(first.utilization()).isEqualTo(40.0);
        assertThat(snapshot.collectingInstances()).containsExactly("n:9400");
    }

    @Test void gpuSeenWithinADayButSilentInWindowIsKeptAsNotCollecting() {
        Map<String, String> gpu = Map.of("instance", "n:9400", "UUID", "GPU-s", "gpu", "3");
        when(metrics.queryResultsAt(eq(DcgmSnapshotLoader.SEEN), any())).thenReturn(List.of(row(gpu, "1")));
        when(metrics.queryResultsAt(startsWith("avg_over_time(DCGM_FI_DEV_GPU_TEMP"), any()))
                .thenReturn(List.of(row(gpu, "NaN")));                                  // NaN 은 값으로 치지 않는다

        DcgmSnapshot snapshot = loader.load(GpuWindow.of(null, null, clock));

        assertThat(snapshot.units()).singleElement().satisfies(u -> {
            assertThat(u.collecting()).isFalse();
            assertThat(u.utilization()).isNull();
            assertThat(u.temperatureC()).isNull();
            assertThat(u.name()).isEqualTo("n:9400 / gpu 3");                           // Hostname 없으면 instance
        });
        assertThat(snapshot.collectingInstances()).isEmpty();
    }

    @Test void infiniteAndNonNumericValuesAreNotCollected() {
        Map<String, String> gpu = Map.of("instance", "n:9400", "UUID", "GPU-i", "gpu", "0");
        when(metrics.queryResultsAt(eq(DcgmSnapshotLoader.SEEN), any())).thenReturn(List.of(row(gpu, "1")));
        when(metrics.queryResultsAt(startsWith("avg_over_time(DCGM_FI_DEV_GPU_UTIL"), any()))
                .thenReturn(List.of(row(gpu, "+Inf")));                                 // NaN 처럼 값으로 치지 않는다
        when(metrics.queryResultsAt(startsWith("avg_over_time(DCGM_FI_DEV_GPU_TEMP"), any()))
                .thenReturn(List.of(row(gpu, "abc")));

        DcgmSnapshot snapshot = loader.load(GpuWindow.of(null, null, clock));

        assertThat(snapshot.units()).singleElement().satisfies(u -> {
            assertThat(u.utilization()).isNull();
            assertThat(u.temperatureC()).isNull();
        });
    }

    @Test void sameWindowIsCachedForTtlAndDifferentWindowsAreSeparate() {
        GpuWindow recent = GpuWindow.of(null, null, clock);
        loader.load(recent);
        loader.load(GpuWindow.of(null, null, clock));
        verify(metrics, times(1)).queryResultsAt(eq(DcgmSnapshotLoader.SEEN), any());   // 기본 창은 같은 키

        GpuWindow yesterday = GpuWindow.of(OffsetDateTime.parse("2026-10-05T00:00:00Z"),
                OffsetDateTime.parse("2026-10-05T06:00:00Z"), clock);
        loader.load(yesterday);
        verify(metrics, times(2)).queryResultsAt(eq(DcgmSnapshotLoader.SEEN), any());

        loader.setClock(Clock.fixed(now.plus(Duration.ofSeconds(21)), ZoneOffset.UTC));
        loader.load(recent);
        verify(metrics, times(3)).queryResultsAt(eq(DcgmSnapshotLoader.SEEN), any());   // 20초 지남

        properties.setCacheTtlSeconds(0);
        loader.load(recent);
        loader.load(recent);
        verify(metrics, times(5)).queryResultsAt(eq(DcgmSnapshotLoader.SEEN), any());   // 0 이면 캐시 안 함
    }

    @Test void unitIdFallsBackToHostAndIndexWithoutUuid() {
        assertThat(DcgmSnapshotLoader.unitId(Map.of("Hostname", "h", "gpu", "2"))).isEqualTo("h:2");
        assertThat(DcgmSnapshotLoader.unitId(Map.of("UUID", "GPU-z", "GPU_I_ID", ""))).isEqualTo("GPU-z");
    }
}
