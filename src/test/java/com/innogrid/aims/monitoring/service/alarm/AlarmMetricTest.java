package com.innogrid.aims.monitoring.service.alarm;

import java.util.List;

import com.innogrid.aims.monitoring.dto.alarm.alarmPolicy.AlarmMetricCatalogResponse;
import com.innogrid.aims.monitoring.service.resourceexplorer.tree.MetricSelector;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 알람 메트릭 카탈로그 — 유형별 묶음·자원 유형 필터, 코드·별칭 해석, PromQL 이 자원 탐색기 노드 패널 표준식과 같은지,
 * 전체 자원 평가용(매처 없음·mdc_id 집계) 식, 기준 문자열.
 */
class AlarmMetricTest {

    @Test
    void 전체_카탈로그는_유형_순서로_묶이고_프론트_옵션_값과_같은_유형_라벨을_쓴다() {
        List<AlarmMetricCatalogResponse> all = AlarmMetric.catalog(null);

        assertThat(all).extracting(AlarmMetricCatalogResponse::metricType)
                .containsExactly("CPU", "Memory", "File System", "GPU");
        assertThat(all.get(1).metrics()).extracting(AlarmMetricCatalogResponse.MetricOption::metric)
                .containsExactly("MEMORY_USED_PERCENT", "MEMORY_AVAILABLE_GB");
        assertThat(all.get(1).metrics().get(1).unit()).isEqualTo("GB");
        assertThat(all.get(2).metrics()).extracting(AlarmMetricCatalogResponse.MetricOption::displayName)
                .containsExactly("FS Used", "Inode Used");
    }

    @Test
    void 자원_유형별로_쓸_수_있는_메트릭만_남는다() {
        assertThat(AlarmMetric.catalog("vm")).extracting(AlarmMetricCatalogResponse::metricType)
                .containsExactly("CPU", "Memory", "File System");          // VM GPU 는 할당 원장 전이라 제외
        assertThat(AlarmMetric.catalog("gpu")).extracting(AlarmMetricCatalogResponse::metricType)
                .containsExactly("GPU");
        assertThat(AlarmMetric.catalog("NODE")).hasSize(4);               // 대소문자 무관
        assertThat(AlarmMetric.catalog("gpu").get(0).metrics()).extracting(AlarmMetricCatalogResponse.MetricOption::metric)
                .containsExactly("GPU_UTIL", "GPU_MEMORY_USED_PERCENT", "GPU_TEMP");
    }

    @ParameterizedTest
    @CsvSource({
            "CPU_USED_PERCENT, CPU_USED_PERCENT",
            "cpu_used_percent, CPU_USED_PERCENT",
            "cpu_usage, CPU_USED_PERCENT",
            "mem_usage, MEMORY_USED_PERCENT",
            "disk_usage, FS_USED_PERCENT",
            "gpu_temperature, GPU_TEMP",
            "GPU_Temperature, GPU_TEMP",
    })
    void 코드값과_초안_별칭을_해석한다(String input, AlarmMetric expected) {
        assertThat(AlarmMetric.resolve(input)).contains(expected);
    }

    @Test
    void 모르는_이름과_빈_값은_비어_있다() {
        assertThat(AlarmMetric.resolve("load1")).isEmpty();
        assertThat(AlarmMetric.resolve("  ")).isEmpty();
        assertThat(AlarmMetric.resolve(null)).isEmpty();
    }

    @Test
    void 자원_하나의_PromQL_은_자원_탐색기_노드_패널_표준식과_같다() {
        MetricSelector s = MetricSelector.instance("10.0.0.1:9100");

        assertThat(AlarmMetric.CPU_USED_PERCENT.promql(s, "5m")).isEqualTo(
                "100 - (avg by (instance) (rate(node_cpu_seconds_total{mode=\"idle\",instance=\"10.0.0.1:9100\"}[5m])) * 100)");
        assertThat(AlarmMetric.MEMORY_USED_PERCENT.promql(s, "5m")).isEqualTo(
                "(1 - avg by (instance) (node_memory_MemAvailable_bytes{instance=\"10.0.0.1:9100\"}"
                        + " / node_memory_MemTotal_bytes{instance=\"10.0.0.1:9100\"})) * 100");
        assertThat(AlarmMetric.MEMORY_AVAILABLE_GB.promql(s, "5m")).isEqualTo(
                "avg by (instance) (node_memory_MemAvailable_bytes{instance=\"10.0.0.1:9100\"}) / 1073741824");
        assertThat(AlarmMetric.FS_USED_PERCENT.promql(s, "5m")).isEqualTo(
                "(1 - sum by (instance) (node_filesystem_avail_bytes{fstype!~\"tmpfs|overlay\",instance=\"10.0.0.1:9100\"})"
                        + " / sum by (instance) (node_filesystem_size_bytes{fstype!~\"tmpfs|overlay\",instance=\"10.0.0.1:9100\"})) * 100");
        assertThat(AlarmMetric.INODE_USED_PERCENT.promql(s, "5m"))
                .contains("node_filesystem_files_free{fstype!~\"tmpfs|overlay\",instance=\"10.0.0.1:9100\"}")
                .contains("node_filesystem_files{fstype!~\"tmpfs|overlay\",instance=\"10.0.0.1:9100\"}");
        assertThat(AlarmMetric.GPU_UTIL.promql(s, "5m")).isEqualTo(
                "avg by (instance) (DCGM_FI_DEV_GPU_UTIL{instance=\"10.0.0.1:9100\"})");
        assertThat(AlarmMetric.GPU_TEMP.promql(s, "5m")).isEqualTo(
                "max by (instance) (DCGM_FI_DEV_GPU_TEMP{instance=\"10.0.0.1:9100\"})");
    }

    @Test
    void Tumblebug_VM_선택자도_그대로_쓴다() {
        MetricSelector vm = MetricSelector.tumblebugNode("ns1", "infra1", "vm1");

        assertThat(AlarmMetric.CPU_USED_PERCENT.promql(vm, "10m")).isEqualTo(
                "100 - (avg by (instance) (rate(node_cpu_seconds_total{mode=\"idle\",tb_ns=\"ns1\",tb_infra=\"infra1\",tb_node=\"vm1\"}[10m])) * 100)");
    }

    @Test
    void 전체_자원_평가용_식은_매처_없이_자원_구분_라벨로_집계한다() {
        assertThat(AlarmMetric.CPU_USED_PERCENT.promql("instance, mdc_id", null, "5m")).isEqualTo(
                "100 - (avg by (instance, mdc_id) (rate(node_cpu_seconds_total{mode=\"idle\"}[5m])) * 100)");
        assertThat(AlarmMetric.MEMORY_USED_PERCENT.promql("instance, mdc_id", "", "5m")).isEqualTo(
                "(1 - avg by (instance, mdc_id) (node_memory_MemAvailable_bytes / node_memory_MemTotal_bytes)) * 100");
        assertThat(AlarmMetric.GPU_MEMORY_USED_PERCENT.promql("instance, gpu", null, "5m")).isEqualTo(
                "sum by (instance, gpu) (DCGM_FI_DEV_FB_USED) / (sum by (instance, gpu) (DCGM_FI_DEV_FB_USED)"
                        + " + sum by (instance, gpu) (DCGM_FI_DEV_FB_FREE)) * 100");
    }

    @Test
    void 기준_문자열은_표시명_연산자_임계값_단위다() {
        assertThat(AlarmMetric.CPU_USED_PERCENT.criterion(">", 80)).isEqualTo("CPU Used > 80%");
        assertThat(AlarmMetric.MEMORY_AVAILABLE_GB.criterion("<", 1.5)).isEqualTo("Memory Available < 1.5GB");
        assertThat(AlarmMetric.GPU_TEMP.criterion(">=", 85)).isEqualTo("GPU Temperature >= 85°C");
    }

    @Test
    void 자원_유형_적용_여부() {
        assertThat(AlarmMetric.GPU_UTIL.appliesTo("vm")).isFalse();
        assertThat(AlarmMetric.GPU_UTIL.appliesTo("node")).isTrue();
        assertThat(AlarmMetric.FS_USED_PERCENT.appliesTo("gpu")).isFalse();
        assertThat(AlarmMetric.FS_USED_PERCENT.appliesTo(null)).isFalse();
    }
}
