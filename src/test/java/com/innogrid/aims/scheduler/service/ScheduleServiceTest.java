package com.innogrid.aims.scheduler.service;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;

import com.innogrid.aims.common.dto.PageResponse;
import com.innogrid.aims.common.exception.BusinessException;
import com.innogrid.aims.scheduler.dto.CronPreviewResponse;
import com.innogrid.aims.scheduler.dto.ScheduleRequest;
import com.innogrid.aims.scheduler.dto.ScheduleResponse;
import com.innogrid.aims.scheduler.entity.Schedule;
import com.innogrid.aims.scheduler.entity.Workload;
import com.innogrid.aims.scheduler.repository.ScheduleRepository;
import com.innogrid.aims.scheduler.repository.ScheduleRunRepository;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@Transactional
class ScheduleServiceTest {

    @Autowired private ScheduleService scheduleService;
    @Autowired private ScheduleRepository scheduleRepository;
    @Autowired private ScheduleRunRepository runRepository;

    @BeforeEach
    void clean() {
        runRepository.deleteAll();
        scheduleRepository.deleteAll();
    }

    // ── 생성 ───────────────────────────────────────────────────

    @DisplayName("스케줄을 생성한다")
    @Test
    void schedule_created() {
        ScheduleResponse created = scheduleService.create(request("nightly", "0 0 2 * * ?"));

        assertNotNull(created.id());
        assertEquals("nightly", created.scheduleId());
        assertEquals(Workload.ResourceType.VM, created.resourceType());
        assertTrue(created.enabled());
        assertFalse(created.autoRun(), "자동 실행은 지정하지 않으면 꺼진 상태다");
    }

    @DisplayName("중복된 스케줄 ID 는 409 이다")
    @Test
    void duplicate_schedule_id_409() {
        scheduleService.create(request("nightly", "0 0 2 * * ?"));

        BusinessException e = assertThrows(BusinessException.class,
                () -> scheduleService.create(request("nightly", "0 0 3 * * ?")));

        assertEquals(HttpStatus.CONFLICT, e.getStatus());
    }

    @DisplayName("잘못된 실행 주기는 400 이다")
    @Test
    void invalid_cron_400() {
        // 도움말 모달의 7필드 예시
        BusinessException e = assertThrows(BusinessException.class,
                () -> scheduleService.create(request("bad", "0 15 10 ? * 6L 2002-2005")));

        assertEquals(HttpStatus.BAD_REQUEST, e.getStatus());
    }

    @DisplayName("스케줄 ID 형식을 검사한다")
    @Test
    void schedule_id_format_validated() {
        assertThrows(BusinessException.class, () -> scheduleService.create(request("한글아이디", "0 0 2 * * ?")));
        assertThrows(BusinessException.class, () -> scheduleService.create(request("_leading", "0 0 2 * * ?")));
        assertDoesNotThrow(() -> scheduleService.create(request("job-1.a_B", "0 0 2 * * ?")));
    }

    @DisplayName("필수값이 비면 400 이다")
    @Test
    void missing_required_value_400() {
        assertThrows(BusinessException.class, () -> scheduleService.create(
                new ScheduleRequest(null, "이름", null, "0 0 2 * * ?", null, null, null, null, null, null, null)));
        assertThrows(BusinessException.class, () -> scheduleService.create(
                new ScheduleRequest("id", " ", null, "0 0 2 * * ?", null, null, null, null, null, null, null)));
    }

    @DisplayName("알 수 없는 엔진은 400 이다")
    @Test
    void unknown_engine_400() {
        ScheduleRequest request = new ScheduleRequest("job", "이름", null, "0 0 2 * * ?",
                null, null, null, "quantum", null, null, null);

        assertEquals(HttpStatus.BAD_REQUEST,
                assertThrows(BusinessException.class, () -> scheduleService.create(request)).getStatus());
    }

    @DisplayName("자동 실행이 켜지면 다음 실행 시각이 계산된다")
    @Test
    void auto_on_computes_next_run_time() {
        ScheduleResponse created = scheduleService.create(
                new ScheduleRequest("auto", "이름", null, "0 0 2 * * ?", true, true, null,
                        null, null, null, null));

        assertNotNull(created.nextRunAt());
        assertEquals(0, created.nextRunAt().getOffset().getTotalSeconds(), "저장은 UTC");
    }

    @DisplayName("자동 실행이 꺼져 있으면 다음 실행 시각이 없다")
    @Test
    void auto_off_has_no_next_run_time() {
        assertNull(scheduleService.create(request("manual", "0 0 2 * * ?")).nextRunAt());
    }

    // ── 편집 ───────────────────────────────────────────────────

    @DisplayName("스케줄 ID 는 변경할 수 없다")
    @Test
    void schedule_id_cannot_change() {
        Long id = scheduleService.create(request("origin", "0 0 2 * * ?")).id();
        ScheduleRequest renamed = new ScheduleRequest("changed", "이름", null, null,
                null, null, null, null, null, null, null);

        assertThrows(BusinessException.class, () -> scheduleService.update(id, renamed));
    }

    @DisplayName("사용 여부를 켜면 다음 실행 시각을 다시 계산한다")
    @Test
    void enabling_recomputes_next_run_time() {
        // 예전 값을 그대로 두면 이미 지난 시각이라 켜자마자 실행된다
        Long id = scheduleService.create(
                new ScheduleRequest("auto", "이름", null, "0 0 2 * * ?", true, false, null,
                        null, null, null, null)).id();
        assertNull(scheduleRepository.findById(id).orElseThrow().getNextRunAt());

        ScheduleResponse enabled = scheduleService.update(id,
                new ScheduleRequest(null, null, null, null, null, true, null, null, null, null, null));

        assertNotNull(enabled.nextRunAt());
    }

    @DisplayName("없는 스케줄은 404 이다")
    @Test
    void missing_schedule_404() {
        assertEquals(HttpStatus.NOT_FOUND,
                assertThrows(BusinessException.class, () -> scheduleService.get(99999L)).getStatus());
    }

    // ── 목록 ───────────────────────────────────────────────────

    @DisplayName("목록은 1부터 시작하는 페이지를 쓴다")
    @Test
    void list_uses_1_based_paging() {
        for (int i = 1; i <= 3; i++) {
            scheduleService.create(request("job-" + i, "0 0 2 * * ?"));
        }
        PageResponse<ScheduleResponse> first = scheduleService.list(null, null, null, 1, 2, "scheduleId,asc");

        assertEquals(1, first.page());
        assertEquals(2, first.items().size());
        assertEquals(3, first.totalElements());
        assertEquals(2, first.totalPages());
        assertEquals("job-1", first.items().get(0).scheduleId());

        PageResponse<ScheduleResponse> second = scheduleService.list(null, null, null, 2, 2, "scheduleId,asc");
        assertEquals(1, second.items().size());
        assertEquals("job-3", second.items().get(0).scheduleId());
    }

    @DisplayName("검색어와 필터가 동작한다")
    @Test
    void keyword_and_filters_work() {
        scheduleService.create(new ScheduleRequest("gpu-job", "지피유", Workload.ResourceType.GPU,
                "0 0 2 * * ?", null, null, null, null, null, null, null));
        scheduleService.create(new ScheduleRequest("vm-job", "브이엠", Workload.ResourceType.VM,
                "0 0 2 * * ?", null, null, null, null, null, null, null));

        assertEquals(1, scheduleService.list("gpu", null, null, 1, 10, null).totalElements());
        assertEquals(1, scheduleService.list(null, Workload.ResourceType.VM, null, 1, 10, null).totalElements());
        assertEquals(2, scheduleService.list(null, null, true, 1, 10, null).totalElements());
    }

    @DisplayName("허용하지 않는 정렬 필드는 400 이다")
    @Test
    void disallowed_sort_field_400() {
        assertEquals(HttpStatus.BAD_REQUEST,
                assertThrows(BusinessException.class,
                        () -> scheduleService.list(null, null, null, 1, 10, "createdBy,asc")).getStatus());
    }

    // ── 삭제 ───────────────────────────────────────────────────

    @DisplayName("여러 건을 한 번에 삭제한다")
    @Test
    void bulk_delete_multiple() {
        Long a = scheduleService.create(request("a", "0 0 2 * * ?")).id();
        Long b = scheduleService.create(request("b", "0 0 2 * * ?")).id();

        scheduleService.delete(List.of(a, b, 99999L));   // 없는 ID 는 무시한다

        assertEquals(0, scheduleRepository.count());
    }

    @DisplayName("삭제할 ID 가 없으면 400 이다")
    @Test
    void empty_delete_id_list_400() {
        assertThrows(BusinessException.class, () -> scheduleService.delete(List.of()));
        assertThrows(BusinessException.class, () -> scheduleService.delete(null));
    }

    // ── 실행 주기 미리보기 ──────────────────────────────────────

    @DisplayName("다음 실행 시각을 요일 이름과 함께 돌려준다")
    @Test
    void next_run_times_include_day_names() {
        CronPreviewResponse preview = scheduleService.previewCron("0 0 2 ? * 2");

        assertEquals("Asia/Seoul", preview.timezone());
        assertEquals(5, preview.nextRuns().size());
        // Quartz 2 = 월요일. 변환하지 않으면 화요일이 나온다.
        assertEquals("월요일", preview.nextRuns().get(0).dayOfWeek());
        assertTrue(preview.nextRuns().get(0).localTime().endsWith("02:00:00"),
                preview.nextRuns().get(0).localTime());
    }

    private ScheduleRequest request(String scheduleId, String cron) {
        return new ScheduleRequest(scheduleId, "테스트 스케줄", Workload.ResourceType.VM, cron,
                null, null, "설명", null, null, null, null);
    }

    @SuppressWarnings("unused")
    private Schedule reload(Long id) {
        return scheduleRepository.findById(id).orElseThrow();
    }
}
