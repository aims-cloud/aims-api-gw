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
 * <p>시드 8건은 {@code wp-} 접두어로 심고 {@code keyword} 로 격리한다(다른 시험의 워크로드와 섞이지 않게).
 * 종류가 비어 있는 행 2건은 {@code resourceType=VM} 이 옛 행을 포함하는 규칙을, 이름에 {@code _}·{@code %} 가 든 행은
 * LIKE 이스케이프를 고정한다.</p>
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
     * 8건 — 종류 VM 3 · GPU 3 · 비어 있음 2, 상태 PENDING 5 · SCHEDULED 1 · FAILED 2.
     * <pre>
     *   0 VM   PENDING            4 GPU  PENDING
     *   1 VM   FAILED(1회)        5 GPU  SCHEDULED(node-a)
     *   2 VM   PENDING  이름 "_"   6 null PENDING
     *   3 GPU  FAILED(소진 · 상한 회) 설명 "%"   7 null PENDING
     * </pre>
     */
    @BeforeEach
    void seed() {
        prefix = "wp-" + Long.toHexString(System.nanoTime());
        Workload.ResourceType[] types = {
                Workload.ResourceType.VM, Workload.ResourceType.VM, Workload.ResourceType.VM, Workload.ResourceType.GPU,
                Workload.ResourceType.GPU, Workload.ResourceType.GPU, null, null };
        for (int i = 0; i < 8; i++) {
            String name = prefix + (i == 2 ? "-under_score" : "-" + i);
            Workload w = new Workload(name, 1 + i, 256 * (i + 1));
            w.setResourceType(types[i]);
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
        // FAILED 인데 retryCount 가 null 인 옛 행(엔티티 메서드로는 못 만듦) — 0회라 소진이 아니어야 한다
        tx.executeWithoutResult(s -> em.createQuery("update Workload w set w.retryCount = null where w.id = :id")
                .setParameter("id", seeded.get(1)).executeUpdate());
        assertEquals(1, total("retryExhausted", "true"), "null 행은 소진에 들지 않는다");
        assertEquals(7, total("retryExhausted", "false"), "null 행은 나머지에 남는다");
        assertEquals(1, total("retryExhausted", "false", "status", "FAILED"), "FAILED 이면서 소진이 아닌 행 = null 행");
        for (JsonNode row : page("keyword", prefix, "resourceType", "VM").get("items")) {
            assertTrue(row.get("resourceType").isNull() || "VM".equals(row.get("resourceType").asString()), row.toString());
        }
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

    @DisplayName("정렬은 허용 필드만 받고 priority 와 모르는 필드·모르는 값은 400 이다")
    @Test
    void sort_and_invalid_values_400() throws Exception {
        List<Long> cpuAsc = ids(page("keyword", prefix, "sort", "requiredCpu,asc").get("items"));
        assertEquals(seeded, cpuAsc, "requiredCpu 1..8 = 시드 순");
        List<Long> nameDesc = ids(page("keyword", prefix, "sort", "name,desc").get("items"));
        assertEquals(List.of(seeded.get(2), seeded.get(7), seeded.get(6), seeded.get(5), seeded.get(4),
                seeded.get(3), seeded.get(1), seeded.get(0)), nameDesc, "이름 내림차순 — '-under_score'('u' 가 숫자보다 큼) 뒤 -7…-0");

        for (String field : List.of("createdAt", "name", "status", "resourceType", "requiredCpu", "requiredMemoryMb",
                "requiredGpu", "requiredDiskGb", "retryCount", "scheduledAt", "lastFailedAt")) {
            page("keyword", prefix, "sort", field + ",desc");   // 허용 필드 11 전부 실제 정렬이 돈다(속성명 오타면 500)
        }
        mockMvc.perform(get(PAGE).param("sort", "priority,asc")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", containsString("createdAt")));
        mockMvc.perform(get(PAGE).param("sort", "nope,asc")).andExpect(status().isBadRequest());
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

    /** keyword 를 직접 주는 경우(격리 접두어를 붙이지 않음). */
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
