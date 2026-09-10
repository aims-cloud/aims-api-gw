package com.innogrid.aims.scheduler.dto;

import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import com.innogrid.aims.scheduler.entity.SchedulingHistory;
import com.innogrid.aims.scheduler.entity.SchedulingPolicy;
import com.innogrid.aims.scheduler.entity.Workload;

import tools.jackson.databind.ObjectMapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 엔티티를 그대로 직렬화하는 응답 3종의 JSON 계약을 고정한다 — 응답 DTO 로 바꾸기 <b>전에</b> 못 박는 기준선.
 *
 * <p>{@code /scheduler/workloads}·{@code /scheduler/history}·{@code /scheduler/policies} 는 JPA 엔티티를
 * DTO 없이 응답으로 내보낸다. 프런트가 이미 쓰고 있을 수 있으므로 DTO 를 도입해도 <b>키 집합과 값이
 * 바이트 단위로 같아야</b> 한다. 이 테스트가 그 "같아야 할 것"의 원본이다.</p>
 *
 * <p>스프링이 구성한 Jackson 3({@code tools.jackson}) 매퍼를 주입받아 쓴다. HTTP 응답을 만드는 것이
 * 그 매퍼이며, {@code com.fasterxml} 쪽은 별개 인스턴스라 계약과 무관하다. 노드 응답(21필드)은
 * {@code NodeResourceSerializationTest} 가 같은 방식으로 고정하고 있다.</p>
 */
@SpringBootTest
public class ResponseJsonGoldenTest {

    /** 워크로드 응답 필드 21개. */
    public static final Set<String> WORKLOAD_FIELDS = new TreeSet<>(Set.of(
            "id", "name", "description",
            "requiredCpu", "requiredMemoryMb", "requiredGpu", "requiredDiskGb",
            "resourceType", "dataMdcId", "preferredMdcId",
            "maxPowerBudgetWatts", "maxTemperatureCelsius", "priority",
            "assignedMdcId", "assignedNodeId", "status",
            "schedulingScore", "schedulingReason", "retryCount", "lastFailedAt",
            "createdAt", "scheduledAt"));

    /** 배치 이력 응답 필드 22개. */
    public static final Set<String> HISTORY_FIELDS = new TreeSet<>(Set.of(
            "id", "workloadId", "workloadName", "selectedMdcId", "selectedNodeId",
            "finalScore", "cpuScore", "memoryScore", "powerScore", "temperatureScore", "networkScore",
            "nodeCpuUsage", "nodeMemoryUsage", "nodePowerWatts", "nodeTemperature",
            "candidateCount", "reason", "engineName", "policyName",
            "scheduleRunId", "success", "createdAt"));

    /** 배치 정책 응답 필드 20개. */
    public static final Set<String> POLICY_FIELDS = new TreeSet<>(Set.of(
            "id", "name", "description", "resourceType", "defaultEngine", "warningTemperatureCelsius",
            "cpuWeight", "memoryWeight", "powerWeight", "temperatureWeight", "networkWeight",
            "maxCpuUsagePercent", "maxMemoryUsagePercent", "maxTemperatureCelsius", "maxPowerUsagePercent",
            "powerAwareEnabled", "temperatureAwareEnabled", "affinityEnabled", "active", "createdAt"));

    @Autowired
    ObjectMapper objectMapper;

    @DisplayName("워크로드 응답 필드가 변하지 않는다")
    @Test
    void workload_fields_unchanged() {
        Map<String, Object> json = toMap(Samples.workload());

        assertEquals(WORKLOAD_FIELDS, new TreeSet<>(json.keySet()),
                "워크로드 응답 필드가 바뀌었다. 의도한 변경이면 WORKLOAD_FIELDS 를 고치고 프런트에 공지한다");
        assertEquals("SCHEDULED", json.get("status"));
        assertEquals("HIGH", json.get("priority"));
        assertEquals("GPU", json.get("resourceType"));
        assertEquals(0, json.get("retryCount"), "배치 성공 시 재시도 횟수가 0 으로 초기화된다");
        assertEquals(0.8123, json.get("schedulingScore"));
        // resolve* 헬퍼는 get 접두어가 없어 응답에 나오지 않는다
        assertEquals(false, json.containsKey("resolveResourceType"));
    }

    @DisplayName("워크로드 빈 값은 null 로 나간다")
    @Test
    void workload_empty_values_null() {
        Map<String, Object> json = toMap(new Workload("bare", 1, 512));

        assertEquals(WORKLOAD_FIELDS, new TreeSet<>(json.keySet()), "값이 비어도 키는 전부 있어야 한다");
        assertNull(json.get("retryCount"), "기존 행처럼 null 이면 null 로 나간다 — 0 으로 바꾸면 계약 위반");
        assertNull(json.get("resourceType"));
        assertEquals("PENDING", json.get("status"));
        assertEquals("NORMAL", json.get("priority"));
    }

    @DisplayName("배치 이력 응답 필드가 변하지 않는다")
    @Test
    void history_fields_unchanged() {
        Map<String, Object> json = toMap(Samples.history());

        assertEquals(HISTORY_FIELDS, new TreeSet<>(json.keySet()),
                "배치 이력 응답 필드가 바뀌었다. 의도한 변경이면 HISTORY_FIELDS 를 고치고 프런트에 공지한다");
        assertEquals(0.7321, json.get("finalScore"));
        assertEquals(3, json.get("candidateCount"));
        assertEquals(true, json.get("success"));
        assertEquals(77L, json.get("scheduleRunId"));
    }

    @DisplayName("성공 여부가 비어 있는 옛 이력 행은 success 가 null 로 나간다")
    @Test
    void history_success_null_for_legacy_row() {
        // 옛 행(컬럼 도입 전 + 단건 배치가 실행 연결 없이 만든 것)은 success 가 비어 있고, 응답은 그 값을 그대로 낸다
        Map<String, Object> json = toMap(new SchedulingHistory(1L, "w", "mdc-01", "n1", 0.5, "r", "weighted", "default"));

        assertEquals(HISTORY_FIELDS, new TreeSet<>(json.keySet()));
        assertNull(json.get("success"));
        assertNull(json.get("scheduleRunId"));
    }

    @DisplayName("배치 정책 응답 필드가 변하지 않는다")
    @Test
    void policy_fields_unchanged() {
        Map<String, Object> json = toMap(Samples.policy());

        assertEquals(POLICY_FIELDS, new TreeSet<>(json.keySet()),
                "배치 정책 응답 필드가 바뀌었다. 의도한 변경이면 POLICY_FIELDS 를 고치고 프런트에 공지한다");
        assertEquals("VM", json.get("resourceType"));
        assertEquals("power", json.get("defaultEngine"));
        assertEquals(0.3, json.get("cpuWeight"));
        assertEquals(false, json.get("powerAwareEnabled"));
        assertEquals(true, json.get("active"));
        assertEquals(60.0, json.get("warningTemperatureCelsius"));
    }

    @DisplayName("배치 정책 기본값은 그대로 나간다")
    @Test
    void policy_defaults_unchanged() {
        Map<String, Object> json = toMap(new SchedulingPolicy("default", "기본"));

        assertEquals(POLICY_FIELDS, new TreeSet<>(json.keySet()));
        assertEquals(0.25, json.get("cpuWeight"));
        assertEquals(80.0, json.get("maxCpuUsagePercent"));
        assertNull(json.get("resourceType"), "공통 정책은 리소스 종류가 null");
        assertNull(json.get("warningTemperatureCelsius"));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> toMap(Object value) {
        return objectMapper.convertValue(value, Map.class);
    }

    /** 세 엔티티의 대표 표본. DTO 동치 테스트도 같은 표본을 쓴다. */
    static final class Samples {
        static Workload workload() {
            Workload w = new Workload("train-job", 4, 8192);
            w.setDescription("골든 표본");
            w.setRequiredGpu(1);
            w.setRequiredDiskGb(100);
            w.setResourceType(Workload.ResourceType.GPU);
            w.setDataMdcId("mdc-02");
            w.setPreferredMdcId("mdc-01");
            w.setMaxPowerBudgetWatts(450.0);
            w.setMaxTemperatureCelsius(70.0);
            w.setPriority(Workload.WorkloadPriority.HIGH);
            w.assignTo("mdc-01", "node-1", 0.8123, "최고 점수");
            return w;
        }

        static SchedulingHistory history() {
            SchedulingHistory h = new SchedulingHistory(11L, "train-job", "mdc-01", "node-1",
                    0.7321, "최고 점수 0.7321", "weighted", "default");
            h.setScoreDetails(0.7, 0.6, 0.5, 0.4, 0.99);
            h.setNodeStatus(30.5, 40.5, 250.0, 55.0);
            h.setCandidateCount(3);
            h.linkToRun(77L, true);
            return h;
        }

        static SchedulingPolicy policy() {
            SchedulingPolicy p = new SchedulingPolicy("gpu-power", "GPU 전력 우선");
            p.setResourceType(Workload.ResourceType.VM);
            p.setDefaultEngine("power");
            p.setWarningTemperatureCelsius(60.0);
            p.setCpuWeight(0.3);
            p.setMemoryWeight(0.2);
            p.setPowerWeight(0.3);
            p.setTemperatureWeight(0.1);
            p.setNetworkWeight(0.1);
            p.setMaxCpuUsagePercent(75);
            p.setMaxMemoryUsagePercent(80);
            p.setMaxTemperatureCelsius(72);
            p.setMaxPowerUsagePercent(85);
            p.setPowerAwareEnabled(false);
            p.setTemperatureAwareEnabled(true);
            p.setAffinityEnabled(true);
            p.setActive(true);
            return p;
        }
    }
}
