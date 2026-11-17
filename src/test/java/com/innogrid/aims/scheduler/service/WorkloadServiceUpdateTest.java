package com.innogrid.aims.scheduler.service;

import java.time.OffsetDateTime;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;

import com.innogrid.aims.common.exception.BusinessException;
import com.innogrid.aims.scheduler.dto.WorkloadRequest;
import com.innogrid.aims.scheduler.dto.WorkloadUpdateRequest;
import com.innogrid.aims.scheduler.entity.Workload;
import com.innogrid.aims.scheduler.repository.WorkloadRepository;

import jakarta.persistence.EntityManager;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 워크로드 편집(부분 갱신) 규칙 — 보낸 키만, 비울 수 있는 여섯 칸의 명시적 null, 생성과 같은 검증, 상태 409, 전부 검증한 뒤 일괄 반영,
 * 재시도 회계 불변.
 *
 * <p>요청은 JSON 문자열을 실제 매퍼로 읽어 만든다 — "키 없음"과 "명시적 null"의 구분이 HTTP와 같은 경로로 생긴다.</p>
 */
@SpringBootTest
@Transactional
class WorkloadServiceUpdateTest {

    @Autowired WorkloadService service;
    @Autowired WorkloadRepository repository;
    @Autowired ObjectMapper objectMapper;
    @Autowired EntityManager em;

    private WorkloadUpdateRequest body(String json) {
        return objectMapper.readValue(json, WorkloadUpdateRequest.class);
    }

    /** 같은 칸을 담은 생성 요청 — 필수 세 칸을 채운 뒤 사례 본문의 키로 덮는다(사례 본문이 이긴다). */
    private WorkloadRequest createBody(String json) {
        ObjectNode merged = (ObjectNode) objectMapper.readTree("{\"name\":\"c\",\"requiredCpu\":1,\"requiredMemoryMb\":1}");
        merged.setAll((ObjectNode) objectMapper.readTree(json));
        return objectMapper.treeToValue(merged, WorkloadRequest.class);
    }

    /** 13칸을 모두 채운 PENDING 워크로드. */
    private Workload full(String name) {
        return service.create(new WorkloadRequest(name, "설명", 2, 2048, 1, 10, "VM", "mdc-data", "mdc-pref",
                300.0, 70.0, 100.0, "HIGH"));
    }

    /** DB에서 다시 읽는다 — 영속성 컨텍스트를 비워 메모리 사본이 아니라 저장된 값을 본다. */
    private Workload reload(Workload w) {
        repository.flush();
        em.clear();
        return repository.findById(w.getId()).orElseThrow();
    }

    private static void assertStatus(HttpStatus expected, Runnable call) {
        BusinessException e = assertThrows(BusinessException.class, call::run);
        assertEquals(expected, e.getStatus(), e.getMessage());
    }

    // ── 부분 갱신 ──────────────────────────────────────────────

    @DisplayName("보낸 키만 바뀌고 나머지 열두 칸은 그대로다")
    @Test
    void partial_update_changes_only_sent_keys() {
        Workload w = full("edit-partial");

        service.update(w.getId(), body("{\"requiredCpu\":4}"));

        Workload r = reload(w);
        assertEquals(4, r.getRequiredCpu());
        assertEquals("edit-partial", r.getName());
        assertEquals("설명", r.getDescription());
        assertEquals(2048, r.getRequiredMemoryMb());
        assertEquals(1, r.getRequiredGpu());
        assertEquals(10, r.getRequiredDiskGb());
        assertEquals(Workload.ResourceType.VM, r.getResourceType());
        assertEquals("mdc-data", r.getDataMdcId());
        assertEquals("mdc-pref", r.getPreferredMdcId());
        assertEquals(300.0, r.getMaxPowerBudgetWatts());
        assertEquals(70.0, r.getMaxTemperatureCelsius());
        assertEquals(100.0, r.getRequiredBandwidthMbps());
        assertEquals(Workload.WorkloadPriority.HIGH, r.getPriority());
    }

    @DisplayName("비울 수 있는 여섯 칸은 명시적 null 이면 비워지고 키를 빼면 유지된다")
    @Test
    void explicit_null_clears_six_clearable_fields() {
        Workload a = full("edit-clear-all");
        service.update(a.getId(), body("{\"description\":null,\"dataMdcId\":null,\"preferredMdcId\":null,"
                + "\"maxPowerBudgetWatts\":null,\"maxTemperatureCelsius\":null,\"requiredBandwidthMbps\":null}"));
        Workload ra = reload(a);
        assertNull(ra.getDescription());
        assertNull(ra.getDataMdcId());
        assertNull(ra.getPreferredMdcId());
        assertNull(ra.getMaxPowerBudgetWatts());
        assertNull(ra.getMaxTemperatureCelsius());
        assertNull(ra.getRequiredBandwidthMbps());

        Workload b = full("edit-clear-one");
        service.update(b.getId(), body("{\"requiredBandwidthMbps\":null}"));
        Workload rb = reload(b);
        assertNull(rb.getRequiredBandwidthMbps());
        assertEquals("설명", rb.getDescription(), "키를 빼면 유지");
        assertEquals("mdc-data", rb.getDataMdcId());
        assertEquals(300.0, rb.getMaxPowerBudgetWatts());
    }

    @DisplayName("비울 수 없는 일곱 칸의 null 은 유지로 읽는다")
    @Test
    void null_on_required_fields_keeps_value() {
        Workload w = full("edit-keep");

        service.update(w.getId(), body("{\"name\":null,\"requiredCpu\":null,\"requiredMemoryMb\":null,\"requiredGpu\":null,"
                + "\"requiredDiskGb\":null,\"resourceType\":null,\"priority\":null}"));

        Workload r = reload(w);
        assertEquals("edit-keep", r.getName());
        assertEquals(2, r.getRequiredCpu());
        assertEquals(2048, r.getRequiredMemoryMb());
        assertEquals(1, r.getRequiredGpu());
        assertEquals(10, r.getRequiredDiskGb());
        assertEquals(Workload.ResourceType.VM, r.getResourceType());
        assertEquals(Workload.WorkloadPriority.HIGH, r.getPriority());
    }

    @DisplayName("값 검증은 생성과 같고 사유 문구까지 같다")
    @Test
    void validation_matches_create() {
        Workload w = full("edit-validate");
        String[][] cases = {
                // 편집 본문과 기대 사유 — 같은 본문의 생성 요청도 같은 사유인지 함께 본다
                {"{\"name\":\"   \"}", "워크로드 이름은(는) 필수입니다"},
                {"{\"name\":\"" + "가".repeat(101) + "\"}", "워크로드 이름은(는) 100자 이내여야 합니다 (현재 101자)"},
                {"{\"requiredCpu\":0}", "요구 CPU은(는) 1 이상이어야 합니다: 0"},
                {"{\"requiredMemoryMb\":0}", "요구 메모리(MB)은(는) 1 이상이어야 합니다: 0"},
                {"{\"requiredGpu\":-1}", "요구 GPU은(는) 0 이상이어야 합니다: -1"},
                {"{\"requiredDiskGb\":-1}", "요구 디스크(GB)은(는) 0 이상이어야 합니다: -1"},
                {"{\"requiredBandwidthMbps\":0}", "요구 대역폭(Mbps)은(는) 유한한 0 보다 큰 수여야 합니다: 0.0"},
                {"{\"maxPowerBudgetWatts\":-5}", "노드 전력 상한(W)은(는) 유한한 0 보다 큰 수여야 합니다: -5.0"},
                {"{\"maxTemperatureCelsius\":0}", "온도 상한(°C)은(는) 유한한 0 보다 큰 수여야 합니다: 0.0"},
                {"{\"description\":\"" + "x".repeat(301) + "\"}", "설명은(는) 300자 이내여야 합니다 (현재 301자)"},
                {"{\"dataMdcId\":\"" + "m".repeat(51) + "\"}", "데이터 MDC은(는) 50자 이내여야 합니다 (현재 51자)"},
                {"{\"preferredMdcId\":\"" + "m".repeat(51) + "\"}", "선호 MDC은(는) 50자 이내여야 합니다 (현재 51자)"},
                {"{\"resourceType\":\"TPU\"}", "지원하지 않는 리소스 종류 입니다: TPU (가능한 값: [VM, GPU, NPU, CONTAINER])"},
                {"{\"priority\":\"URGENT\"}", "지원하지 않는 우선순위 입니다: URGENT (가능한 값: [LOW, NORMAL, HIGH, CRITICAL])"},
        };
        for (String[] c : cases) {
            BusinessException e = assertThrows(BusinessException.class, () -> service.update(w.getId(), body(c[0])), c[0]);
            assertEquals(HttpStatus.BAD_REQUEST, e.getStatus(), c[0]);
            assertEquals(c[1], e.getMessage(), c[0]);
            BusinessException created = assertThrows(BusinessException.class, () -> service.create(createBody(c[0])), c[0]);
            assertEquals(c[1], created.getMessage(), "생성과 같은 사유: " + c[0]);
        }
    }

    @DisplayName("비울 수 있는 칸에 잘못된 JSON 타입이 오면 400 이다 — 숫자 칸의 숫자 문자열도")
    @Test
    void clearable_fields_reject_wrong_json_type() {
        Workload w = full("edit-type");
        BusinessException numeric = assertThrows(BusinessException.class,
                () -> service.update(w.getId(), body("{\"requiredBandwidthMbps\":\"100\"}")));
        assertEquals("요구 대역폭(Mbps)은(는) 숫자여야 합니다", numeric.getMessage());
        assertStatus(HttpStatus.BAD_REQUEST, () -> service.update(w.getId(), body("{\"maxPowerBudgetWatts\":true}")));
        assertStatus(HttpStatus.BAD_REQUEST, () -> service.update(w.getId(), body("{\"maxTemperatureCelsius\":{\"v\":1}}")));
        BusinessException text = assertThrows(BusinessException.class,
                () -> service.update(w.getId(), body("{\"description\":5}")));
        assertEquals("설명은(는) 문자열이어야 합니다", text.getMessage(), "생성은 숫자를 문자열로 받아 주지만 편집은 400");
        assertStatus(HttpStatus.BAD_REQUEST, () -> service.update(w.getId(), body("{\"dataMdcId\":[\"a\"]}")));
        assertStatus(HttpStatus.BAD_REQUEST, () -> service.update(w.getId(), body("{\"preferredMdcId\":5}")));
        Workload r = reload(w);
        assertEquals(100.0, r.getRequiredBandwidthMbps());
        assertEquals("설명", r.getDescription());
        assertEquals("mdc-pref", r.getPreferredMdcId());
    }

    @DisplayName("double 로 나타낼 수 없는 수(너무 크거나 작은 수)도 400 이고 값은 그대로다")
    @Test
    void out_of_range_numbers_rejected_with_400() {
        Workload w = full("edit-huge");
        String huge = "1" + "0".repeat(310);
        String[][] cases = {
                {"{\"requiredBandwidthMbps\":" + huge + "}", "요구 대역폭(Mbps)은(는) 유한한 0 보다 큰 수여야 합니다: Infinity"},
                {"{\"maxPowerBudgetWatts\":-" + huge + "}", "노드 전력 상한(W)은(는) 유한한 0 보다 큰 수여야 합니다: -Infinity"},
                {"{\"maxTemperatureCelsius\":1e400}", "온도 상한(°C)은(는) 유한한 0 보다 큰 수여야 합니다: Infinity"},
                {"{\"requiredBandwidthMbps\":1e-400}", "요구 대역폭(Mbps)은(는) 유한한 0 보다 큰 수여야 합니다: 0.0"},
        };
        for (String[] c : cases) {
            BusinessException e = assertThrows(BusinessException.class, () -> service.update(w.getId(), body(c[0])), c[0]);
            assertEquals(HttpStatus.BAD_REQUEST, e.getStatus(), c[0]);
            assertEquals(c[1], e.getMessage(), c[0]);
        }
        Workload r = reload(w);
        assertEquals(100.0, r.getRequiredBandwidthMbps());
        assertEquals(300.0, r.getMaxPowerBudgetWatts());
        assertEquals(70.0, r.getMaxTemperatureCelsius());
    }

    @DisplayName("MDC 칸은 생성처럼 앞뒤 공백을 지우고 빈 문자열은 비움으로 본다")
    @Test
    void mdc_ids_trimmed_and_blank_clears() {
        Workload w = full("edit-mdc");

        service.update(w.getId(), body("{\"dataMdcId\":\"  mdc-02  \",\"preferredMdcId\":\"   \"}"));

        Workload r = reload(w);
        assertEquals("mdc-02", r.getDataMdcId());
        assertNull(r.getPreferredMdcId());
    }

    @DisplayName("이름은 생성처럼 앞뒤 공백을 지우고 설명은 생성처럼 그대로 둔다 — 빈 설명도 빈 문자열로 남는다")
    @Test
    void name_trimmed_and_description_kept_verbatim() {
        Workload w = full("edit-trim");
        String json = "{\"name\":\"  edit-trim-2  \",\"description\":\"  설명  \"}";

        service.update(w.getId(), body(json));

        Workload r = reload(w);
        assertEquals("edit-trim-2", r.getName());
        assertEquals("  설명  ", r.getDescription());
        Workload created = service.create(createBody(json));
        assertEquals("edit-trim-2", created.getName(), "생성도 이름을 다듬는다");
        assertEquals("  설명  ", created.getDescription(), "생성도 설명을 다듬지 않는다");

        service.update(w.getId(), body("{\"description\":\"\"}"));
        assertEquals("", reload(w).getDescription(), "비우려면 null 을 보내야 한다");
    }

    @DisplayName("종류는 빈 문자열로 비울 수 없다 — 400 이고 값은 그대로다")
    @Test
    void resource_type_cannot_be_cleared() {
        Workload w = full("edit-type-blank");

        BusinessException e = assertThrows(BusinessException.class,
                () -> service.update(w.getId(), body("{\"resourceType\":\"\"}")));

        assertEquals(HttpStatus.BAD_REQUEST, e.getStatus());
        assertEquals("리소스 종류 값이 필요합니다", e.getMessage());
        assertEquals(Workload.ResourceType.VM, reload(w).getResourceType());
    }

    @DisplayName("한 칸이라도 400 이면 다른 칸도 반영되지 않는다")
    @Test
    void invalid_field_leaves_workload_untouched() {
        Workload w = full("edit-atomic");

        assertStatus(HttpStatus.BAD_REQUEST, () -> service.update(w.getId(),
                body("{\"requiredCpu\":8,\"description\":null,\"requiredBandwidthMbps\":0}")));

        Workload r = reload(w);
        assertEquals(2, r.getRequiredCpu(), "앞 칸이 먼저 반영되면 안 된다");
        assertEquals("설명", r.getDescription());
        assertEquals(100.0, r.getRequiredBandwidthMbps());
    }

    // ── 상태 ───────────────────────────────────────────────────

    @DisplayName("배치·실행·완료 상태는 409 이고 배치는 예약 취소를 안내한다")
    @Test
    void non_editable_states_409_with_guidance() {
        Workload scheduled = full("edit-scheduled");
        scheduled.assignTo("mdc-01", "n1", 0.7, "배치");
        repository.saveAndFlush(scheduled);
        BusinessException s = assertThrows(BusinessException.class, () -> service.update(scheduled.getId(), body("{\"requiredCpu\":1}")));
        assertEquals(HttpStatus.CONFLICT, s.getStatus());
        assertEquals("배치된 워크로드는 편집할 수 없습니다 (상태 SCHEDULED, 노드 n1). "
                + "먼저 예약 취소(POST /workloads/{id}/release)로 배정을 푸세요", s.getMessage());

        Workload running = full("edit-running");
        running.assignTo("mdc-01", "n1", 0.7, "배치");
        running.transitionTo(Workload.WorkloadStatus.RUNNING);
        repository.saveAndFlush(running);
        BusinessException r = assertThrows(BusinessException.class, () -> service.update(running.getId(), body("{\"requiredCpu\":1}")));
        assertEquals(HttpStatus.CONFLICT, r.getStatus());
        assertEquals("실행 중인 워크로드는 편집할 수 없습니다 (상태 RUNNING, 노드 n1)", r.getMessage());

        Workload completed = full("edit-completed");
        completed.assignTo("mdc-01", "n1", 0.7, "배치");
        completed.transitionTo(Workload.WorkloadStatus.RUNNING);
        completed.transitionTo(Workload.WorkloadStatus.COMPLETED);
        repository.saveAndFlush(completed);
        BusinessException c = assertThrows(BusinessException.class, () -> service.update(completed.getId(), body("{\"requiredCpu\":1}")));
        assertEquals(HttpStatus.CONFLICT, c.getStatus());
        assertEquals("완료된 워크로드는 실행 기록이라 편집할 수 없습니다 — 다시 돌리려면 새로 생성하세요", c.getMessage());

        assertEquals(2, reload(scheduled).getRequiredCpu());
        assertEquals(2, reload(running).getRequiredCpu());
        assertEquals(2, reload(completed).getRequiredCpu());
    }

    @DisplayName("상태 검사가 본문 검증보다 먼저다 — 배치된 워크로드에 틀린 본문을 보내도 409")
    @Test
    void state_check_precedes_body_validation() {
        Workload w = full("edit-order");
        w.assignTo("mdc-01", "n1", 0.7, "배치");
        repository.saveAndFlush(w);

        assertStatus(HttpStatus.CONFLICT, () -> service.update(w.getId(), body("{\"requiredCpu\":0}")));
        assertStatus(HttpStatus.CONFLICT, () -> service.update(w.getId(), null));
    }

    @DisplayName("실패 워크로드를 편집해도 재시도 회계·사유·배정·시각은 그대로다")
    @Test
    void retry_accounting_and_decision_columns_unchanged() {
        Workload w = full("edit-retry");
        w.assignTo("mdc-01", "n1", 0.7, "배치");
        for (int i = 0; i < 5; i++) w.markFailed("자원 부족");
        repository.saveAndFlush(w);
        Workload before = reload(w);
        OffsetDateTime lastFailed = before.getLastFailedAt();
        OffsetDateTime scheduledAt = before.getScheduledAt();
        OffsetDateTime createdAt = before.getCreatedAt();

        service.update(w.getId(), body("{\"requiredCpu\":1,\"name\":\"edit-retry-2\"}"));

        Workload r = reload(w);
        assertEquals(1, r.getRequiredCpu());
        assertEquals("edit-retry-2", r.getName());
        assertEquals(Workload.WorkloadStatus.FAILED, r.getStatus());
        assertEquals(5, r.getRetryCount(), "편집은 숨은 되살리기가 아니다");
        assertEquals(lastFailed, r.getLastFailedAt());
        assertEquals("자원 부족", r.getSchedulingReason());
        assertEquals("n1", r.getAssignedNodeId());
        assertEquals(0.7, r.getSchedulingScore());
        assertEquals(scheduledAt, r.getScheduledAt());
        assertEquals(createdAt, r.getCreatedAt());
    }

    // ── 오류 · 경계 ────────────────────────────────────────────

    @DisplayName("없는 워크로드는 404 이고 본문이 없으면 400 이다")
    @Test
    void missing_workload_404_and_missing_body_400() {
        assertStatus(HttpStatus.NOT_FOUND, () -> service.update(999_999_999L, body("{}")));
        Workload w = full("edit-nobody");
        BusinessException e = assertThrows(BusinessException.class, () -> service.update(w.getId(), null));
        assertEquals(HttpStatus.BAD_REQUEST, e.getStatus());
        assertEquals("요청 본문이 필요합니다", e.getMessage());
    }

    @DisplayName("없는 워크로드는 본문이 없거나 틀려도 404 다")
    @Test
    void missing_workload_404_precedes_body_errors() {
        assertStatus(HttpStatus.NOT_FOUND, () -> service.update(999_999_999L, null));
        assertStatus(HttpStatus.NOT_FOUND, () -> service.update(999_999_999L, body("{\"requiredCpu\":0}")));
    }

    @DisplayName("빈 본문은 아무것도 바꾸지 않는다")
    @Test
    void empty_body_is_no_op() {
        Workload w = full("edit-empty");

        Workload r = service.update(w.getId(), body("{}"));

        assertEquals("edit-empty", r.getName());
        assertEquals(2, r.getRequiredCpu());
        assertEquals("설명", r.getDescription());
        assertEquals(100.0, r.getRequiredBandwidthMbps());
    }

    @DisplayName("이름 중복은 생성처럼 막지 않는다")
    @Test
    void duplicate_name_allowed_like_create() {
        full("edit-dup-a");
        Workload b = full("edit-dup-b");

        service.update(b.getId(), body("{\"name\":\"edit-dup-a\"}"));

        assertEquals("edit-dup-a", reload(b).getName());
    }

    @DisplayName("종류를 바꾸면 스케줄 실행 후보 풀이 함께 옮겨 간다")
    @Test
    void resource_type_change_moves_candidate_pool() {
        Workload w = full("edit-pool");
        List<Workload.WorkloadStatus> targets = List.of(Workload.WorkloadStatus.PENDING, Workload.WorkloadStatus.FAILED);
        OffsetDateTime far = OffsetDateTime.now().plusYears(1);

        service.update(w.getId(), body("{\"resourceType\":\"gpu\"}"));
        repository.flush();

        assertTrue(repository.findSchedulingCandidates(targets, Workload.ResourceType.GPU, false, 5, far)
                .stream().anyMatch(x -> x.getId().equals(w.getId())), "GPU 후보에 들어간다");
        assertTrue(repository.findSchedulingCandidates(targets, Workload.ResourceType.VM, true, 5, far)
                .stream().noneMatch(x -> x.getId().equals(w.getId())), "VM 후보에서는 빠진다");
    }
}
