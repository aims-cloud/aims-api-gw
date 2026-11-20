package com.innogrid.aims.monitoring.service.resourceexplorer.tree;

import com.innogrid.aims.monitoring.dto.resourceexplorer.ResourceStatus;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

/** Tumblebug 상태값 → §6.1 버킷 — Node 단어 상태, Infra 집계 상태(구분자·개수 접미·Partial 접두). */
class TumblebugStatusMapperTest {

    @ParameterizedTest
    @CsvSource({
            "Running, RUNNING",
            "Creating, PENDING", "Registering, PENDING", "Reconciling, PENDING", "Suspending, PENDING",
            "Resuming, PENDING", "Rebooting, PENDING", "Terminating, PENDING", "Preparing, PENDING", "Prepared, PENDING",
            "Suspended, STOP", "Terminated, STOP",
            "Failed, ERROR", "Undefined, ERROR", "Unknown, ERROR",
    })
    void Node_상태_단어를_버킷으로_묶는다(String status, ResourceStatus expected) {
        assertThat(TumblebugStatusMapper.bucket(status)).isEqualTo(expected);
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "Running:1 (R:1/1)        | RUNNING",
            "Running-(3/3)            | RUNNING",
            "Running (R:3/3)          | RUNNING",
            "Creating:2 (R:0/2)       | PENDING",
            "Terminated:3 (R:0/3)     | STOP",
            "Failed:1 (R:0/1)         | ERROR",
            "Partial-Running:2 (R:2/3)| PENDING",
            "Partial-Failed:1 (R:1/2) | ERROR",
            "Partial-Terminated:1     | STOP",
            "  Running:1              | RUNNING",
    })
    void Infra_집계_상태는_접두_접미를_떼고_상태_단어로_묶는다(String status, ResourceStatus expected) {
        assertThat(TumblebugStatusMapper.bucket(status)).isEqualTo(expected);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   "})
    void 빈_상태는_ERROR다(String status) {
        assertThat(TumblebugStatusMapper.bucket(status)).isEqualTo(ResourceStatus.ERROR);
    }
}
