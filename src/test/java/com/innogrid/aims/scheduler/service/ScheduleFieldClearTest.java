package com.innogrid.aims.scheduler.service;

import java.time.OffsetDateTime;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

import com.innogrid.aims.common.exception.BusinessException;
import com.innogrid.aims.scheduler.dto.ScheduleRequest;
import com.innogrid.aims.scheduler.dto.ScheduleResponse;
import com.innogrid.aims.scheduler.dto.ScheduleRunPreviewResponse;
import com.innogrid.aims.scheduler.dto.ScheduleUpdateRequest;
import com.innogrid.aims.scheduler.entity.Schedule;
import com.innogrid.aims.scheduler.entity.SchedulingPolicy;
import com.innogrid.aims.scheduler.entity.Workload;
import com.innogrid.aims.scheduler.model.EngineSource;
import com.innogrid.aims.scheduler.model.PolicyChoice;
import com.innogrid.aims.scheduler.repository.ScheduleRepository;
import com.innogrid.aims.scheduler.repository.ScheduleRunRepository;
import com.innogrid.aims.scheduler.repository.SchedulingPolicyRepository;
import com.innogrid.aims.scheduler.repository.WorkloadRepository;

import jakarta.persistence.EntityManager;
import tools.jackson.databind.ObjectMapper;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

/**
 * 스케줄 편집 지정 해제 — 비울 수 있는 다섯 칸의 명시적 null, 키 없음 유지, 타입 엄격 400, 전부 검증 뒤 일괄 반영.
 *
 * <p>요청은 JSON 문자열을 실제 매퍼로 읽어 만든다 — "키 없음"과 "명시적 null"의 구분이 HTTP와 같은 경로로 생긴다.</p>
 */
@SpringBootTest
@Transactional
class ScheduleFieldClearTest {

    @Autowired private ScheduleService service;
    @Autowired private ScheduleExecutionService executionService;
    @Autowired private ScheduleRepository scheduleRepository;
    @Autowired private ScheduleRunRepository runRepository;
    @Autowired private WorkloadRepository workloadRepository;
    @Autowired private SchedulingPolicyRepository policyRepository;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private EntityManager em;
    @MockitoBean private NodeResourceCollector collector;

    private SchedulingPolicy policy;

    @BeforeEach
    void clean() {
        runRepository.deleteAll();
        scheduleRepository.deleteAll();
        workloadRepository.deleteAll();
        policyRepository.deleteAll();
        policyRepository.flush();
        when(collector.collect()).thenAnswer(inv -> new NodeResourceCollector.Snapshot(List.of(), List.of()));
        SchedulingPolicy p = new SchedulingPolicy("common", "common");
        p.setResourceType(null);
        p.setActive(true);
        p.setDefaultEngine("temperature");
        p.setMaxTemperatureCelsius(80.0);
        policy = policyRepository.save(p);
    }

    private ScheduleUpdateRequest body(String json) {
        return objectMapper.readValue(json, ScheduleUpdateRequest.class);
    }

    /** 다섯 칸을 모두 채운 스케줄(사용 안 함 · 자동 실행 안 함). */
    private Long full(String key) {
        return service.create(new ScheduleRequest(key, "이름 " + key, Workload.ResourceType.VM, "0 0 2 * * ?", false, false,
                "설명", "power", policy.getId(), 500.0, 70.0, "size")).id();
    }

    private Schedule reload(Long id) {
        scheduleRepository.flush();
        em.clear();
        return scheduleRepository.findById(id).orElseThrow();
    }

    private static void assertStatus(HttpStatus expected, Runnable call) {
        BusinessException e = assertThrows(BusinessException.class, call::run);
        assertEquals(expected, e.getStatus(), e.getMessage());
    }

    // ── 비움과 유지 ────────────────────────────────────────────

    @DisplayName("정책 id 에 null 을 보내면 비워져 상속으로 돌아가고 키를 빼면 유지된다")
    @Test
    void policy_id_null_clears_and_absent_keeps() {
        Long id = full("clr-policy");
        assertEquals(PolicyChoice.Source.SCHEDULE, executionService.preview(id).policySource());

        service.update(id, body("{\"policyId\":null}"));

        Schedule r = reload(id);
        assertNull(r.getPolicyId());
        assertEquals("power", r.getEngine(), "보내지 않은 칸은 유지");
        assertEquals(PolicyChoice.Source.COMMON, executionService.preview(id).policySource());
        service.update(id, body("{\"name\":\"다른 이름\"}"));
        assertNull(reload(id).getPolicyId(), "키를 빼면 비운 상태가 유지된다");
    }

    @DisplayName("엔진에 null 이나 공백을 보내면 비워져 정책 기본 엔진을 쓴다")
    @Test
    void engine_null_or_blank_clears_and_preview_falls_back_to_policy() {
        Long a = full("clr-engine-a");
        service.update(a, body("{\"engine\":null}"));
        assertNull(reload(a).getEngine());
        ScheduleRunPreviewResponse p = executionService.preview(a);
        assertEquals("temperature", p.engine());
        assertEquals(EngineSource.POLICY, p.engineSource());

        Long b = full("clr-engine-b");
        service.update(b, body("{\"engine\":\"   \"}"));
        assertNull(reload(b).getEngine());
    }

    @DisplayName("전력 상한에 null 을 보내면 비워져 제약을 쓰지 않는다")
    @Test
    void power_budget_null_clears() {
        Long id = full("clr-power");

        service.update(id, body("{\"maxPowerBudgetWatts\":null}"));

        Schedule r = reload(id);
        assertNull(r.getMaxPowerBudgetWatts());
        assertEquals(70.0, r.getMaxTemperatureCelsius(), "다른 칸은 유지");
    }

    @DisplayName("온도 상한에 null 을 보내면 비워져 정책 값을 따르고 정책은 그대로다")
    @Test
    void temperature_null_clears_and_policy_untouched() {
        Long id = full("clr-temp");

        service.update(id, body("{\"maxTemperatureCelsius\":null}"));

        assertNull(reload(id).getMaxTemperatureCelsius());
        assertEquals(80.0, policyRepository.findById(policy.getId()).orElseThrow().getMaxTemperatureCelsius());
    }

    @DisplayName("설명은 null 이나 빈 문자열이면 비워지고 앞뒤 공백은 지워진다")
    @Test
    void description_null_or_empty_clears() {
        Long id = full("clr-desc");
        service.update(id, body("{\"description\":null}"));
        assertNull(reload(id).getDescription());

        service.update(id, body("{\"description\":\"  다시  \"}"));
        assertEquals("다시", reload(id).getDescription());

        service.update(id, body("{\"description\":\"\"}"));
        assertNull(reload(id).getDescription());
    }

    @DisplayName("스케줄명·실행 주기·리소스 종류·배치 순서·자동 실행·사용 여부의 null 은 유지로 읽는다")
    @Test
    void non_clearable_fields_null_keep() {
        Long id = full("clr-keep");

        service.update(id, body("{\"name\":null,\"cronExpression\":null,\"resourceType\":null,\"placementOrder\":null,"
                + "\"autoRun\":null,\"enabled\":null}"));

        Schedule r = reload(id);
        assertEquals("이름 clr-keep", r.getName());
        assertEquals("0 0 2 * * ?", r.getCronExpression());
        assertEquals(Workload.ResourceType.VM, r.getResourceType());
        assertEquals(Schedule.PlacementOrder.PRIORITY_THEN_SIZE, r.getPlacementOrder());
        assertFalse(r.isAutoRun());
        assertFalse(r.isEnabled());
    }

    // ── 타입 엄격 ──────────────────────────────────────────────

    @DisplayName("숫자 칸에 숫자 문자열을 보내면 400 이고 값은 그대로다")
    @Test
    void numeric_strings_rejected_for_number_fields() {
        Long id = full("clr-numstr");
        BusinessException temp = assertThrows(BusinessException.class,
                () -> service.update(id, body("{\"maxTemperatureCelsius\":\"70\"}")));
        assertEquals(HttpStatus.BAD_REQUEST, temp.getStatus());
        assertEquals("온도 상한(℃)은(는) 숫자여야 합니다", temp.getMessage());
        assertStatus(HttpStatus.BAD_REQUEST, () -> service.update(id, body("{\"maxPowerBudgetWatts\":\"500\"}")));
        assertStatus(HttpStatus.BAD_REQUEST, () -> service.update(id, body("{\"maxPowerBudgetWatts\":true}")));

        Schedule r = reload(id);
        assertEquals(500.0, r.getMaxPowerBudgetWatts());
        assertEquals(70.0, r.getMaxTemperatureCelsius());
    }

    @DisplayName("정책 id 는 long 범위 정수만 받는다")
    @Test
    void policy_id_must_be_integral_long() {
        Long id = full("clr-policy-type");
        for (String v : List.of("\"3\"", "1.5", "true", "1" + "0".repeat(30), "{\"v\":1}")) {
            BusinessException e = assertThrows(BusinessException.class,
                    () -> service.update(id, body("{\"policyId\":" + v + "}")), v);
            assertEquals(HttpStatus.BAD_REQUEST, e.getStatus(), v);
            assertEquals("배치 정책 id은(는) 정수여야 합니다", e.getMessage(), v);
        }
        assertEquals(policy.getId(), reload(id).getPolicyId());
    }

    @DisplayName("엔진이 문자열이 아니거나 등록되지 않은 값이면 400 이다")
    @Test
    void engine_non_string_or_unknown_rejected() {
        Long id = full("clr-engine-type");
        BusinessException type = assertThrows(BusinessException.class, () -> service.update(id, body("{\"engine\":5}")));
        assertEquals("엔진은(는) 문자열이어야 합니다", type.getMessage());
        BusinessException unknown = assertThrows(BusinessException.class,
                () -> service.update(id, body("{\"engine\":\"no-such-engine\"}")));
        assertEquals("알 수 없는 스케줄링 엔진입니다: no-such-engine", unknown.getMessage());
        assertEquals("power", reload(id).getEngine());
    }

    // ── 순서 · 원자성 ──────────────────────────────────────────

    @DisplayName("한 칸이라도 400 이면 다른 칸도 반영되지 않는다")
    @Test
    void failed_validation_applies_nothing() {
        Long id = full("clr-atomic");

        assertStatus(HttpStatus.BAD_REQUEST, () -> service.update(id,
                body("{\"name\":\"새 이름\",\"policyId\":null,\"maxTemperatureCelsius\":\"x\"}")));

        Schedule r = reload(id);
        assertEquals("이름 clr-atomic", r.getName(), "앞 칸이 먼저 반영되면 안 된다");
        assertEquals(policy.getId(), r.getPolicyId());
    }

    @DisplayName("스케줄 ID 불일치는 칸 오류보다 먼저 보고된다")
    @Test
    void schedule_id_mismatch_reported_before_field_errors() {
        Long id = full("clr-sid");

        BusinessException e = assertThrows(BusinessException.class,
                () -> service.update(id, body("{\"scheduleId\":\"other\",\"maxTemperatureCelsius\":\"x\"}")));

        assertEquals(HttpStatus.BAD_REQUEST, e.getStatus());
        assertEquals("스케줄 ID 는 변경할 수 없습니다", e.getMessage());
        service.update(id, body("{\"scheduleId\":\"clr-sid\",\"policyId\":null}"));
        assertNull(reload(id).getPolicyId(), "같은 스케줄 ID 는 통과한다");
    }

    @DisplayName("없는 스케줄은 본문이 없거나 틀려도 404 다")
    @Test
    void missing_schedule_404_before_body_checks() {
        assertStatus(HttpStatus.NOT_FOUND, () -> service.update(999_999_999L, null));
        assertStatus(HttpStatus.NOT_FOUND, () -> service.update(999_999_999L, body("{\"policyId\":\"x\"}")));
    }

    @DisplayName("본문이 없으면 400 이다")
    @Test
    void null_body_rejected() {
        Long id = full("clr-nobody");
        BusinessException e = assertThrows(BusinessException.class, () -> service.update(id, null));
        assertEquals(HttpStatus.BAD_REQUEST, e.getStatus());
        assertEquals("요청 본문이 필요합니다", e.getMessage());
    }

    @DisplayName("비우기도 편집이라 수정자가 찍히고 다음 실행 시각 규칙은 그대로다")
    @Test
    void clear_stamps_updated_by_and_keeps_next_run_rule() {
        Long id = full("clr-stamp");
        Schedule stale = reload(id);
        stale.setUpdatedBy("old", "old");   // 편집이 다시 찍는지 보려고 표식값을 심어 둔다
        scheduleRepository.saveAndFlush(stale);
        em.clear();
        OffsetDateTime before = reload(id).getUpdatedAt();

        ScheduleResponse cleared = service.update(id, body("{\"policyId\":null}"));
        assertEquals(ScheduleActorResolver.SYSTEM, cleared.updatedBy(), "인증 없는 호출은 시스템 주체로 다시 찍힌다");
        assertFalse(cleared.updatedAt().isBefore(before));
        assertNull(cleared.nextRunAt(), "자동 실행이 꺼져 있으면 다음 실행 시각은 없다");

        ScheduleResponse enabled = service.update(id, body("{\"autoRun\":true,\"enabled\":true}"));
        assertNotNull(enabled.nextRunAt(), "자동 실행과 사용 여부를 켜면 다음 실행 시각을 계산한다");
        assertNull(enabled.policyId(), "앞서 비운 값은 그대로다");
    }
}
