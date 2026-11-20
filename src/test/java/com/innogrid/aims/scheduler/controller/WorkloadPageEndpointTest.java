package com.innogrid.aims.scheduler.controller;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.support.TransactionTemplate;

import com.innogrid.aims.scheduler.config.SchedulerProperties;
import com.innogrid.aims.scheduler.dto.ResponseJsonGoldenTest;
import com.innogrid.aims.scheduler.entity.Workload;
import com.innogrid.aims.scheduler.repository.WorkloadRepository;

import jakarta.persistence.EntityManager;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@code GET /scheduler/workloads/page} — 총건수·필터·정렬·동률·페이지 경계, 그리고 기존 {@code /workloads} 배열이 그대로인가.
 *
 * <p>시드 8건은 {@code wp-} 접두어로 심고 {@code keyword}로 격리한다(다른 테스트의 워크로드와 섞이지 않게).
 * 종류가 비어 있는 행 2건은 {@code resourceType=VM}이 옛 행을 포함하는 규칙을, 이름에 {@code _}·{@code %}가 든 행은
 * LIKE 이스케이프를 확인한다.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@WithMockUser
class WorkloadPageEndpointTest {

    private static final String PAGE = "/api/v1/scheduler/workloads/page";
    private static final String LIST = "/api/v1/scheduler/workloads";

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired WorkloadRepository repository;
    @Autowired SchedulerProperties properties;
    @Autowired EntityManager em;
    @Autowired TransactionTemplate tx;

    private String prefix;
    private final List<Long> seeded = new ArrayList<>();

    /**
     * 8건 — 종류 VM 3 · GPU 3 · 비어 있음 2, 상태 PENDING 5 · SCHEDULED 1 · FAILED 2,
     * 우선순위 LOW 1 · NORMAL 3(세터를 안 불러 기본값) · HIGH 2 · CRITICAL 2.
     * <pre>
     *   0 VM   PENDING   LOW                 4 GPU  PENDING   HIGH
     *   1 VM   FAILED(1회)   HIGH            5 GPU  SCHEDULED(node-a)   CRITICAL
     *   2 VM   PENDING  이름 "_"   (NORMAL)  6 null PENDING   (NORMAL)
     *   3 GPU  FAILED(소진 · 상한 회) 설명 "%"   CRITICAL   7 null PENDING   (NORMAL)
     * </pre>
     */
    @BeforeEach
    void seed() {
        prefix = "wp-" + Long.toHexString(System.nanoTime());
        Workload.ResourceType[] types = {
                Workload.ResourceType.VM, Workload.ResourceType.VM, Workload.ResourceType.VM, Workload.ResourceType.GPU,
                Workload.ResourceType.GPU, Workload.ResourceType.GPU, null, null };
        Workload.WorkloadPriority[] priorities = {
                Workload.WorkloadPriority.LOW, Workload.WorkloadPriority.HIGH, null, Workload.WorkloadPriority.CRITICAL,
                Workload.WorkloadPriority.HIGH, Workload.WorkloadPriority.CRITICAL, null, null };   // null = 세터 안 부름 → 기본 NORMAL
        for (int i = 0; i < 8; i++) {
            String name = prefix + (i == 2 ? "-under_score" : "-" + i);
            Workload w = new Workload(name, 1 + i, 256 * (i + 1));
            w.setResourceType(types[i]);
            if (priorities[i] != null) w.setPriority(priorities[i]);
            if (i == 3) w.setDescription("100% " + prefix);
            if (i == 1) w.markFailed("시험용");
            if (i == 3) for (int k = 0; k < properties.getMaxRetryCount(); k++) w.markFailed("시험용");   // 재시도 소진
            if (i == 5) w.assignTo("mdc-01", "node-a", 0.5, "시험용");
            seeded.add(repository.save(w).getId());
        }
    }

    @AfterEach
    void clean() {
        repository.deleteAllById(seeded);
    }

    @DisplayName("총건수·기본 정렬은 기존 배열과 같은 집합이고 페이지 응답 형태를 지킨다")
    @Test
    void total_and_default_order_match_array() throws Exception {
        JsonNode d = page("keyword", prefix);
        assertEquals(8, d.get("totalElements").asLong());
        assertEquals(1, d.get("page").asLong());
        assertEquals(20, d.get("size").asLong());
        assertEquals(1, d.get("totalPages").asLong());
        List<Long> ids = ids(d.get("items"));
        for (int i = 1; i < ids.size(); i++) assertTrue(ids.get(i - 1) > ids.get(i), "기본 최신순 = id 내림차순(createdAt 은 삽입 순 증가) " + ids);

        String body = mockMvc.perform(get(LIST)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        Set<Long> array = new HashSet<>();
        for (JsonNode row : objectMapper.readTree(body).get("data")) {
            if (row.get("name").asString().startsWith(prefix)) array.add(row.get("id").asLong());
        }
        assertEquals(new HashSet<>(seeded), array, "기존 배열은 그대로 전량");
        assertEquals(array, new HashSet<>(ids), "페이징 집합 == 배열 집합");
        Set<String> keys = new TreeSet<>();
        d.get("items").get(0).propertyNames().forEach(keys::add);
        assertEquals(ResponseJsonGoldenTest.WORKLOAD_FIELDS, keys, "행은 WorkloadResponse 23필드 그대로");
    }

    @DisplayName("상태·종류·키워드·노드 필터는 단독으로도 조합으로도 걸리고 VM 은 종류가 빈 행을 포함한다")
    @Test
    void filters_narrow_total() throws Exception {
        assertEquals(5, total("status", "pending"), "대소문자 무시");
        assertEquals(2, total("status", "FAILED"));
        assertEquals(1, total("status", "scheduled"));
        assertEquals(5, total("resourceType", "vm"), "VM 3 + 비어 있음 2");
        assertEquals(3, total("resourceType", "GPU"));
        assertEquals(0, total("resourceType", "NPU"));
        assertEquals(1, total("assignedNodeId", "node-a"));
        assertEquals(0, total("assignedNodeId", "node-b"));
        assertEquals(4, total("status", "PENDING", "resourceType", "VM"), "AND 조합");
        assertEquals(1, total("status", "FAILED", "resourceType", "GPU"));
        assertEquals(1, total("status", "FAILED", "resourceType", "VM"), "VM 의 OR 는 괄호로 묶인다 — 종류가 빈 PENDING 행이 섞이면 안 된다");
        assertEquals(8, total("status", "", "resourceType", "", "assignedNodeId", ""), "빈 값은 필터 없음");
        assertEquals(7, total("status", "PENDING", "status", "failed"), "status 반복은 OR");
        assertEquals(7, total("status", "PENDING,FAILED"), "status 쉼표 목록도 OR");
        assertEquals(3, total("status", "scheduled, Failed"), "쉼표 뒤 공백·대소문자 섞임");
        assertEquals(8, total("status", ","), "쉼표만이면 필터 없음");
        assertEquals(1, total("status", "SCHEDULED", "assignedNodeId", "node-a"), "상태와 노드 조합");
        assertEquals(0, total("status", "PENDING", "assignedNodeId", "node-a"));
        assertEquals(1, total("assignedNodeId", " node-a "), "노드 id 앞뒤 공백은 뺀다");
        assertEquals(5, total("resourceType", " Vm "), "열거값은 대소문자 섞임·앞뒤 공백 허용");
        assertEquals(5, total("status", " Pending "));
        assertTrue(properties.getMaxRetryCount() >= 2, "아래 기대값은 상한 ≥ 2 를 전제로 한다(1회 실패 행이 소진이 아니어야 함)");
        assertEquals(1, total("retryExhausted", "true"), "소진 = FAILED 이고 retryCount >= 상한 — 3번 행");
        assertEquals(7, total("retryExhausted", "false"), "나머지 — FAILED 1회 행과 retryCount null 행 포함");
        assertEquals(0, total("retryExhausted", "true", "status", "PENDING"));
        assertEquals(1, total("retryExhausted", "true", "status", "FAILED,PENDING"));
        assertEquals(1, total("retryExhausted", "true", "resourceType", "GPU"));
        assertEquals(8, total("retryExhausted", ""), "빈 값은 필터 없음");
        for (JsonNode row : page("keyword", prefix, "retryExhausted", "true").get("items")) {
            assertEquals("FAILED", row.get("status").asString());
            assertTrue(row.get("retryCount").asInt() >= properties.getMaxRetryCount(), row.toString());
        }
        // FAILED인데 retryCount가 null인 옛 행(엔티티 메서드로는 못 만듦) — 0회라 소진이 아니어야 한다
        tx.executeWithoutResult(s -> em.createQuery("update Workload w set w.retryCount = null where w.id = :id")
                .setParameter("id", seeded.get(1)).executeUpdate());
        assertEquals(1, total("retryExhausted", "true"), "null 행은 소진에 들지 않는다");
        assertEquals(7, total("retryExhausted", "false"), "null 행은 나머지에 남는다");
        assertEquals(1, total("retryExhausted", "false", "status", "FAILED"), "FAILED 이면서 소진이 아닌 행 = null 행");
        for (JsonNode row : page("keyword", prefix, "resourceType", "VM").get("items")) {
            assertTrue(row.get("resourceType").isNull() || "VM".equals(row.get("resourceType").asString()), row.toString());
        }
    }

    @DisplayName("우선순위 필터는 여러 값을 반복·쉼표로 받아 OR 이고 대소문자를 무시하며 다른 필터와 AND 로 겹친다")
    @Test
    void priority_filter_multi_value_or() throws Exception {
        assertEquals(1, total("priority", "LOW"));
        assertEquals(3, total("priority", "normal"), "대소문자 무시 · 세터를 안 부른 행은 기본 NORMAL");
        assertEquals(2, total("priority", " High "), "앞뒤 공백 허용");
        assertEquals(2, total("priority", "CRITICAL"));
        assertEquals(8, total("priority", "LOW") + total("priority", "NORMAL") + total("priority", "HIGH") + total("priority", "CRITICAL"),
                "네 값이 전체를 나눈다 — 우선순위가 빈 행은 없다");
        assertEquals(4, total("priority", "HIGH", "priority", "critical"), "반복은 OR");
        assertEquals(4, total("priority", "HIGH,CRITICAL"), "쉼표 목록도 OR");
        assertEquals(4, total("priority", "low, Normal"), "쉼표 뒤 공백·대소문자 섞임");
        assertEquals(5, total("priority", "HIGH,LOW", "priority", "critical"), "반복과 쉼표를 섞어도 전부 OR — 프레임워크 변환이 아니라 수동 분해가 받는 형태");
        assertEquals(2, total("priority", "HIGH,HIGH"), "같은 값 반복은 한 번");
        assertEquals(8, total("priority", ","), "쉼표만이면 필터 없음");
        assertEquals(8, total("priority", ""), "빈 값은 필터 없음");
        // 다른 필터와 AND
        assertEquals(1, total("priority", "CRITICAL", "status", "SCHEDULED"), "5번 행");
        assertEquals(1, total("priority", "CRITICAL", "retryExhausted", "true"), "3번 행");
        assertEquals(1, total("priority", "HIGH", "resourceType", "VM"), "1번 행 — GPU 인 4번은 빠진다");
        assertEquals(3, total("priority", "NORMAL", "resourceType", "vm"), "2번 + 종류가 빈 6·7번 — VM 의 OR 가 괄호로 묶인다");
        assertEquals(1, total("priority", "HIGH,CRITICAL", "assignedNodeId", "node-a"));
        assertEquals(0, total("priority", "LOW", "status", "FAILED"));
        assertEquals(2, total("priority", "HIGH", "status", "PENDING,FAILED"));
        // 걸러진 행의 집합·값·응답 형태
        JsonNode d = page("keyword", prefix, "priority", "high,critical");
        assertEquals(Set.of(seeded.get(1), seeded.get(3), seeded.get(4), seeded.get(5)), new HashSet<>(ids(d.get("items"))));
        for (JsonNode row : d.get("items")) {
            assertTrue(Set.of("HIGH", "CRITICAL").contains(row.get("priority").asString()), row.toString());
            Set<String> keys = new TreeSet<>();
            row.propertyNames().forEach(keys::add);
            assertEquals(ResponseJsonGoldenTest.WORKLOAD_FIELDS, keys, "필터를 걸어도 행은 23필드 그대로");
        }
        // 모르는 값은 400과 가능한 값 목록 — 필터와 등급 순 정렬은 함께 쓸 수 있다
        mockMvc.perform(get(PAGE).param("priority", "urgent")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", containsString("CRITICAL")));
        mockMvc.perform(get(PAGE).param("priority", "HIGH,nope")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", containsString("LOW")));
        assertEquals(List.of(seeded.get(1), seeded.get(4)), ids(page("keyword", prefix, "priority", "HIGH", "sort", "priority,asc").get("items")),
                "필터 HIGH 두 행을 등급 순 asc 로 — 같은 등급이라 id 오름차순");
    }

    @DisplayName("우선순위 정렬은 알파벳순이 아니라 등급 순이고 같은 등급은 id 로 이어지며 페이지 경계에서 겹치거나 빠지지 않는다")
    @Test
    void priority_sort_is_rank_order_with_id_tiebreak() throws Exception {
        List<Long> s = seeded;
        List<Long> asc = List.of(s.get(0), s.get(2), s.get(6), s.get(7), s.get(1), s.get(4), s.get(3), s.get(5));
        List<Long> desc = List.of(s.get(5), s.get(3), s.get(4), s.get(1), s.get(7), s.get(6), s.get(2), s.get(0));
        JsonNode ascItems = page("keyword", prefix, "sort", "priority,asc").get("items");
        assertEquals(asc, ids(ascItems), "LOW → NORMAL(2·6·7 id 오름차순) → HIGH(1·4) → CRITICAL(3·5) — 알파벳순이면 CRITICAL 이 먼저 온다");
        assertEquals("LOW", ascItems.get(0).get("priority").asString());
        assertEquals(desc, ids(page("keyword", prefix, "sort", "priority,desc").get("items")),
                "CRITICAL(5·3 id 내림차순) → HIGH(4·1) → NORMAL(7·6·2) → LOW — 같은 등급은 첫 키 방향으로 id");
        assertEquals(asc, ids(page("keyword", prefix, "sort", "priority").get("items")), "방향 생략은 asc");
        assertEquals(asc, ids(page("keyword", prefix, "sort", "priority,ASC").get("items")), "방향 대소문자 무시");

        List<Long> stitched = new ArrayList<>();
        for (int p = 1; p <= 3; p++) {
            JsonNode d = page("keyword", prefix, "sort", "priority,asc", "size", "3", "page", String.valueOf(p));
            assertEquals(8, d.get("totalElements").asLong(), "count 질의는 등급 정렬을 지운 채 세어도 총건수가 같다");
            assertEquals(3, d.get("totalPages").asLong());
            for (JsonNode row : d.get("items")) stitched.add(row.get("id").asLong());
        }
        assertEquals(asc, stitched, "세 페이지(3·3·2)를 이어 붙이면 전체와 같다 — NORMAL 이 1·2쪽 경계에 걸쳐도 겹침·빠짐 없음");

        assertEquals(List.of(s.get(5), s.get(3), s.get(4), s.get(1)),
                ids(page("keyword", prefix, "priority", "HIGH,CRITICAL", "sort", "priority,desc").get("items")), "필터와 함께");
        assertEquals(List.of(s.get(0), s.get(2), s.get(6), s.get(7), s.get(4)),
                ids(page("keyword", prefix, "status", "PENDING", "sort", "priority,asc").get("items")), "상태 필터와 함께 — FAILED 1·3, SCHEDULED 5 제외");
        mockMvc.perform(get(PAGE).param("sort", "priority,sideways")).andExpect(status().isBadRequest());
    }

    @DisplayName("우선순위·상태 허용값 문서는 열거형과 같다")
    @Test
    void page_allowable_values_match_the_enums() {
        java.lang.reflect.Method m = java.util.Arrays.stream(SchedulerController.class.getDeclaredMethods())
                .filter(x -> x.getName().equals("getWorkloadPage")).findFirst().orElseThrow();
        java.util.Map<String, Set<String>> declared = new java.util.HashMap<>();
        for (java.lang.reflect.Parameter p : m.getParameters()) {
            io.swagger.v3.oas.annotations.Parameter doc = p.getAnnotation(io.swagger.v3.oas.annotations.Parameter.class);
            if (doc == null || doc.schema().allowableValues().length == 0) continue;
            String[] values = doc.schema().allowableValues();
            Set<String> set = new HashSet<>(java.util.Arrays.asList(values));
            assertEquals(values.length, set.size(), "허용값에 중복이 있다: " + doc.description());
            if (doc.description().startsWith("우선순위")) declared.put("priority", set);
            if (doc.description().startsWith("상태")) declared.put("status", set);
        }
        assertEquals(Set.of("priority", "status"), declared.keySet(),
                "허용값을 선언한 파라미터는 상태·우선순위 둘 — 설명 첫말('상태 —'·'우선순위 —')로 찾는다");
        assertEquals(java.util.Arrays.stream(Workload.WorkloadPriority.values()).map(Enum::name).collect(java.util.stream.Collectors.toSet()),
                declared.get("priority"), "우선순위 허용값이 열거형과 갈렸다");
        assertEquals(java.util.Arrays.stream(Workload.WorkloadStatus.values()).map(Enum::name).collect(java.util.stream.Collectors.toSet()),
                declared.get("status"), "상태 허용값이 열거형과 갈렸다");
    }

    @DisplayName("키워드는 이름·설명의 소문자 부분 일치이고 % 와 _ 는 문자 그대로 찾는다")
    @Test
    void keyword_matches_name_and_description_literally() throws Exception {
        assertEquals(8, total0("keyword", prefix.toUpperCase()), "대소문자 무시");
        assertEquals(1, total0("keyword", prefix + "-under_score"), "이름 부분 일치");
        assertEquals(1, total0("keyword", "100% " + prefix), "설명 부분 일치");
        assertEquals(0, total0("keyword", prefix + "-under%score"), "% 는 와일드카드가 아니다");
        assertEquals(0, total0("keyword", prefix + "_0"), "_ 는 와일드카드가 아니다 — 와일드카드면 '-0' 에 걸린다");
        assertEquals(1, total0("keyword", prefix + "-under_"), "_ 를 문자 그대로");
        assertEquals(8, total0("keyword", "  " + prefix + "  "), "키워드 앞뒤 공백은 뺀다");
        assertEquals(total0(), total0("keyword", "   "), "공백만이면 필터 없음");
    }

    @DisplayName("정렬은 허용 필드만 받고 모르는 필드·모르는 값은 400 이다")
    @Test
    void sort_and_invalid_values_400() throws Exception {
        List<Long> cpuAsc = ids(page("keyword", prefix, "sort", "requiredCpu,asc").get("items"));
        assertEquals(seeded, cpuAsc, "requiredCpu 1..8 = 시드 순");
        List<Long> nameDesc = ids(page("keyword", prefix, "sort", "name,desc").get("items"));
        assertEquals(List.of(seeded.get(2), seeded.get(7), seeded.get(6), seeded.get(5), seeded.get(4),
                seeded.get(3), seeded.get(1), seeded.get(0)), nameDesc, "이름 내림차순 — '-under_score'('u' 가 숫자보다 큼) 뒤 -7…-0");

        for (String field : List.of("createdAt", "name", "status", "resourceType", "priority", "requiredCpu", "requiredMemoryMb",
                "requiredGpu", "requiredDiskGb", "retryCount", "scheduledAt", "lastFailedAt")) {
            page("keyword", prefix, "sort", field + ",desc");   // 허용 필드 12 전부 실제 정렬이 돈다(속성명 오타면 500)
        }
        mockMvc.perform(get(PAGE).param("sort", "nope,asc")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", containsString("createdAt")))
                .andExpect(jsonPath("$.message", containsString("priority")));
        mockMvc.perform(get(PAGE).param("status", "nope")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", containsString("PENDING")));
        mockMvc.perform(get(PAGE).param("resourceType", "disk")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", containsString("VM")));
        mockMvc.perform(get(PAGE).param("size", "101")).andExpect(status().isBadRequest());
        mockMvc.perform(get(PAGE).param("size", "0")).andExpect(status().isBadRequest());
        mockMvc.perform(get(PAGE).param("page", "0")).andExpect(status().isBadRequest());
        mockMvc.perform(get(PAGE).param("status", "PENDING,nope")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", containsString("PENDING")));
        mockMvc.perform(get(PAGE).param("retryExhausted", "nope")).andExpect(status().isBadRequest());
    }

    @DisplayName("총 페이지를 넘는 요청은 빈 목록과 원래 총건수를 주고 size 상한 100 은 허용된다")
    @Test
    void page_beyond_total_is_empty_with_total_kept() throws Exception {
        JsonNode d = page("keyword", prefix, "size", "3", "page", "99");
        assertEquals(0, d.get("items").size());
        assertEquals(8, d.get("totalElements").asLong());
        assertEquals(3, d.get("totalPages").asInt());
        assertEquals(99, d.get("page").asInt());
        assertEquals(8, page("keyword", prefix, "size", "100").get("items").size(), "size 상한 100 은 허용");
    }

    @DisplayName("null 동률이 큰 정렬도 페이지를 이어 붙이면 중복·누락이 없다")
    @Test
    void null_heavy_sort_is_stable_across_pages() throws Exception {
        for (String sort : List.of("lastFailedAt,desc", "scheduledAt,asc", "retryCount,desc")) {
            List<Long> joined = new ArrayList<>();
            for (int p = 1; p <= 3; p++) {
                joined.addAll(ids(page("keyword", prefix, "sort", sort, "size", "3", "page", String.valueOf(p)).get("items")));
            }
            assertEquals(8, joined.size(), sort);
            assertEquals(new HashSet<>(seeded), new HashSet<>(joined), sort + " 중복·누락 없음");
        }
    }

    @DisplayName("같은 값으로 정렬된 행은 id 로 한 번 더 정렬돼 페이지를 이어 붙여도 겹치거나 빠지지 않는다")
    @Test
    void ties_are_stable_across_pages() throws Exception {
        List<Long> joined = new ArrayList<>();
        int pages = 0;
        for (int p = 1; ; p++) {
            JsonNode d = page("keyword", prefix, "sort", "status,desc", "size", "3", "page", String.valueOf(p));
            joined.addAll(ids(d.get("items")));
            pages = d.get("totalPages").asInt();
            if (p >= pages) break;
        }
        assertEquals(3, pages);
        assertEquals(8, joined.size(), "3+3+2");
        assertEquals(8, new HashSet<>(joined).size(), "중복 0");
        assertEquals(new HashSet<>(seeded), new HashSet<>(joined), "누락 0");
        // 상태 동률 안에서는 id 내림차순(1차 키 방향 추종)
        List<JsonNode> rows = new ArrayList<>();
        for (int p = 1; p <= pages; p++) {
            page("keyword", prefix, "sort", "status,desc", "size", "3", "page", String.valueOf(p)).get("items").forEach(rows::add);
        }
        int comparedDesc = 0;
        for (int i = 1; i < rows.size(); i++) {
            if (rows.get(i - 1).get("status").asString().equals(rows.get(i).get("status").asString())) {
                comparedDesc++;
                assertTrue(rows.get(i - 1).get("id").asLong() > rows.get(i).get("id").asLong(), "동률 구간 id 내림차순");
            }
        }
        assertEquals(5, comparedDesc, "동률 인접 쌍 5(PENDING 4 + FAILED 1) — 시드가 바뀌어 동률이 사라지면 여기서 잡힌다");
        // 오름차순이면 동률 구간이 id 오름차순 — 상태가 같은 인접 행만 본다
        JsonNode all = page("keyword", prefix, "sort", "status,asc", "size", "100").get("items");
        int comparedAsc = 0;
        for (int i = 1; i < all.size(); i++) {
            if (all.get(i - 1).get("status").asString().equals(all.get(i).get("status").asString())) {
                comparedAsc++;
                assertTrue(all.get(i - 1).get("id").asLong() < all.get(i).get("id").asLong(), "동률 구간 id 오름차순");
            }
        }
        assertEquals(5, comparedAsc);
    }

    // ── 도우미 ────────────────────────────────────────────────

    private long total(String... pairs) throws Exception {
        String[] withPrefix = new String[pairs.length + 2];
        withPrefix[0] = "keyword";
        withPrefix[1] = prefix;
        System.arraycopy(pairs, 0, withPrefix, 2, pairs.length);
        return page(withPrefix).get("totalElements").asLong();
    }

    /** keyword를 직접 주는 경우(격리 접두어를 붙이지 않음). */
    private long total0(String... pairs) throws Exception {
        return page(pairs).get("totalElements").asLong();
    }

    private JsonNode page(String... pairs) throws Exception {
        MockHttpServletRequestBuilder req = get(PAGE);
        for (int i = 0; i + 1 < pairs.length; i += 2) req = req.param(pairs[i], pairs[i + 1]);
        String body = mockMvc.perform(req).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).get("data");
    }

    private static List<Long> ids(JsonNode items) {
        List<Long> out = new ArrayList<>();
        for (JsonNode row : items) out.add(row.get("id").asLong());
        return out;
    }
}
