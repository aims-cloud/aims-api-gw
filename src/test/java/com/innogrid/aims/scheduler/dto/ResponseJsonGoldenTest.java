package com.innogrid.aims.scheduler.dto;

import java.util.ArrayList;
import java.util.List;
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
import com.innogrid.aims.scheduler.model.NodeResource;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 워크로드·배치 이력·배치 정책 응답의 JSON 계약을 고정한다.
 *
 * <p>{@code /scheduler/workloads}·{@code /scheduler/history}·{@code /scheduler/policies}는 예전에 JPA 엔티티를
 * 그대로 응답으로 내보냈고, 지금은 응답 DTO가 같은 키와 값을 낸다. 프런트가 이미 쓰고 있으므로 <b>키 집합과 값이
 * 바이트 단위로 같아야</b> 한다. 이 테스트가 그 "같아야 할 것"의 원본이다.</p>
 *
 * <p>스프링이 구성한 Jackson 3({@code tools.jackson}) 매퍼를 주입받아 쓴다. HTTP 응답을 만드는 것이
 * 그 매퍼이며, {@code com.fasterxml} 쪽은 별개 인스턴스라 계약과 무관하다. 노드 응답(27필드)의 키 집합은
 * {@code NodeResourceSerializationTest}가 같은 방식으로 고정하고 있다.</p>
 *
 * <p>키 <b>순서</b>는 {@code *_ORDER}가 응답 DTO의 선언 순서로 고정한다. 키 집합 상수 {@code *_FIELDS}는 그 순서 목록에서
 * 만들어 출처를 하나로 둔다. 응답은 record DTO를 선언 순서로 직렬화하므로, 매퍼로 직렬화한 순서가 곧 HTTP 응답의 순서다.</p>
 */
@SpringBootTest
public class ResponseJsonGoldenTest {

    /** 워크로드 응답 키 23개 — {@code WorkloadResponse} 선언 순서. */
    public static final List<String> WORKLOAD_ORDER = List.of(
            "id", "name", "description",
            "requiredCpu", "requiredMemoryMb", "requiredGpu", "requiredDiskGb",
            "resourceType", "dataMdcId", "preferredMdcId",
            "maxPowerBudgetWatts", "maxTemperatureCelsius", "requiredBandwidthMbps", "priority",
            "assignedMdcId", "assignedNodeId", "status",
            "schedulingScore", "schedulingReason", "retryCount", "lastFailedAt",
            "createdAt", "scheduledAt");

    /** 배치 이력 응답 키 23개 — {@code SchedulingHistoryResponse} 선언 순서. */
    public static final List<String> HISTORY_ORDER = List.of(
            "id", "workloadId", "workloadName", "selectedMdcId", "selectedNodeId",
            "finalScore", "cpuScore", "memoryScore", "powerScore", "temperatureScore", "networkScore",
            "nodeCpuUsage", "nodeMemoryUsage", "nodePowerWatts", "nodeTemperature",
            "candidateCount", "reason", "engineName", "policyName",
            "scheduleRunId", "success", "createdAt", "exclusionCodes");

    /** 배치 정책 응답 키 20개 — {@code SchedulingPolicyResponse} 선언 순서. */
    public static final List<String> POLICY_ORDER = List.of(
            "id", "name", "description", "resourceType", "defaultEngine", "warningTemperatureCelsius",
            "cpuWeight", "memoryWeight", "powerWeight", "temperatureWeight", "networkWeight",
            "maxCpuUsagePercent", "maxMemoryUsagePercent", "maxTemperatureCelsius", "maxPowerUsagePercent",
            "powerAwareEnabled", "temperatureAwareEnabled", "affinityEnabled", "active", "createdAt");

    /** 노드 응답 키 27개 — {@code NodeResponse} 선언 순서(가용량 넷은 끝). */
    public static final List<String> NODE_ORDER = List.of(
            "mdcId", "nodeId", "totalCpu", "totalMemoryMb", "totalGpu", "diskCapacityGb",
            "cpuUsagePercent", "memoryUsagePercent", "diskUsagePercent", "powerWatts", "powerCapacityWatts", "temperatureCelsius",
            "networkRxBps", "networkTxBps", "gpuUsagePercent", "gpuTemperatureCelsius", "gpuPowerWatts",
            "reservedCpu", "reservedMemoryMb", "reservedGpu", "reservedDiskGb", "thermalState", "powerSource",
            "availableCpu", "availableMemoryMb", "availableGpu", "availableDiskGb");

    /** 키 집합 — 순서 목록에서 만든다(중복이 있으면 크기가 달라 순서 테스트에서 잡힌다). */
    public static final Set<String> WORKLOAD_FIELDS = new TreeSet<>(WORKLOAD_ORDER);
    public static final Set<String> HISTORY_FIELDS = new TreeSet<>(HISTORY_ORDER);
    public static final Set<String> POLICY_FIELDS = new TreeSet<>(POLICY_ORDER);

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
        assertEquals(1000.0, json.get("requiredBandwidthMbps"), "요구 대역폭은 엔티티 값 그대로 나간다");
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
        assertNull(json.get("requiredBandwidthMbps"), "안 준 워크로드는 null — 예전 행과 같다");
        assertEquals("PENDING", json.get("status"));
        assertEquals("NORMAL", json.get("priority"));
    }

    @DisplayName("배치 이력 응답 필드가 변하지 않는다")
    @Test
    void history_fields_unchanged() {
        Map<String, Object> json = toMap(Samples.history());

        assertEquals(HISTORY_FIELDS, new TreeSet<>(json.keySet()),
                "배치 이력 응답 필드가 바뀌었다. 의도한 변경이면 HISTORY_FIELDS 를 고치고 프런트에 공지한다");
        assertEquals("{\"INSUFFICIENT_RESOURCE\":2}", json.get("exclusionCodes"),
                "제외 코드 집계는 객체가 아니라 문자열로 나간다 — 화면이 파싱한다는 계약");
        assertEquals(0.7321, json.get("finalScore"));
        assertEquals(3, json.get("candidateCount"));
        assertEquals(true, json.get("success"));
        assertEquals(77L, json.get("scheduleRunId"));
    }

    @DisplayName("성공 여부가 비어 있는 옛 이력 행은 success 가 null 로 나간다")
    @Test
    void history_success_null_for_legacy_row() {
        // 옛 행(컬럼 도입 전 + 단건 배치가 실행 연결 없이 만든 것)은 success가 비어 있고, 응답은 그 값을 그대로 낸다
        Map<String, Object> json = toMap(new SchedulingHistory(1L, "w", "mdc-01", "n1", 0.5, "r", "weighted", "default"));

        assertEquals(HISTORY_FIELDS, new TreeSet<>(json.keySet()));
        assertNull(json.get("exclusionCodes"), "제외 코드 집계가 생기기 전의 행은 null — 빈 객체가 아니다");
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

    @DisplayName("워크로드 응답 DTO 는 키를 선언 순서로 낸다")
    @Test
    void workload_response_keeps_declared_order() {
        assertEquals(WORKLOAD_ORDER.size(), WORKLOAD_FIELDS.size(), "순서 목록에 중복 키 없음");
        assertEquals(WORKLOAD_ORDER, names(WorkloadResponse.from(Samples.workload())));
    }

    @DisplayName("배치 이력 응답 DTO 는 키를 선언 순서로 낸다")
    @Test
    void history_response_keeps_declared_order() {
        assertEquals(HISTORY_ORDER.size(), HISTORY_FIELDS.size());
        assertEquals(HISTORY_ORDER, names(SchedulingHistoryResponse.from(Samples.history())));
    }

    @DisplayName("배치 정책 응답 DTO 는 키를 선언 순서로 낸다")
    @Test
    void policy_response_keeps_declared_order() {
        assertEquals(POLICY_ORDER.size(), POLICY_FIELDS.size());
        assertEquals(POLICY_ORDER, names(SchedulingPolicyResponse.from(Samples.policy())));
    }

    @DisplayName("노드 응답 DTO 는 키를 선언 순서로 낸다")
    @Test
    void node_response_keeps_declared_order() {
        assertEquals(27, new TreeSet<>(NODE_ORDER).size());
        NodeResource node = new NodeResource("mdc-01", "node-1:9100", 8, 65_536, 1, 10, 20, 5, 250, 500, 45, 1_000, 500);
        assertEquals(NODE_ORDER, names(NodeResponse.from(node.withDiskCapacityGb(500))));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> toMap(Object value) {
        return objectMapper.convertValue(value, Map.class);
    }

    /** 매퍼로 직렬화해 다시 읽은 키 목록 — 순서를 보존한다. */
    private List<String> names(Object dto) {
        JsonNode node = objectMapper.readTree(objectMapper.writeValueAsString(dto));
        List<String> keys = new ArrayList<>();
        node.propertyNames().forEach(keys::add);
        return keys;
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
            w.setRequiredBandwidthMbps(1000.0);
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
            h.setExclusionCodes("{\"INSUFFICIENT_RESOURCE\":2}");
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
