package com.innogrid.aims.scheduler.service;

import java.util.ArrayList;
import java.util.Comparator;
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
import com.innogrid.aims.scheduler.dto.ScheduleUpdateRequest;
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

    // ── 컬럼 길이 ────────────────────────────────────────

    /**
     * 컬럼 길이를 넘는 값이 <b>400 으로</b> 막히는지.
     *
     * <p>검증이 없으면 INSERT 가 컬럼 길이에서 실패하는데, 그 예외를 잡는 곳이 없어 배포 환경에서는
     * {@code /error} 재요청이 인증에 막혀 클라이언트가 <b>403(본문 없음)</b> 을 받는다(실측). 여기(H2)에서는
     * {@code DataIntegrityViolationException} 이 그대로 튀어나오므로 <b>MockMvc 가 아니라 서비스 계층</b>에서
     * 확인해야 한다 — MockMvc 는 {@code perform()} 자체가 던져 단언에 도달하지 못한다.</p>
     */
    @DisplayName("스케줄명이 컬럼 길이를 넘으면 400 이고 사유에 현재 길이가 있다")
    @Test
    void name_over_column_length_400() {
        BusinessException e = assertThrows(BusinessException.class,
                () -> scheduleService.create(request("len-name", "가".repeat(Schedule.NAME_MAX + 1), null, null)));

        assertEquals(HttpStatus.BAD_REQUEST, e.getStatus());
        assertTrue(e.getMessage().contains(String.valueOf(Schedule.NAME_MAX)), e.getMessage());
        assertTrue(e.getMessage().contains(String.valueOf(Schedule.NAME_MAX + 1)), e.getMessage());
    }

    @DisplayName("유효하지만 긴 cron 이 컬럼 길이를 넘으면 400 — 분을 전부 나열하면 179자가 된다")
    @Test
    void long_but_valid_cron_over_column_length_400() {
        StringBuilder minutes = new StringBuilder();
        for (int i = 0; i < 60; i++) {
            if (i > 0) minutes.append(',');
            minutes.append(i);
        }
        String cron = "0 " + minutes + " * * * ?";
        assertTrue(cron.length() > Schedule.CRON_MAX, "표현식이 상한을 넘어야 이 테스트가 의미를 갖는다");

        BusinessException e = assertThrows(BusinessException.class,
                () -> scheduleService.create(request("len-cron", "이름", cron, null)));

        assertEquals(HttpStatus.BAD_REQUEST, e.getStatus());
        assertTrue(e.getMessage().contains(String.valueOf(Schedule.CRON_MAX)), e.getMessage());
    }

    @DisplayName("정확히 상한 길이는 통과하고 저장까지 된다 — 검증 상수와 컬럼 길이가 어긋나면 여기서 INSERT 가 터진다")
    @Test
    void exactly_at_limit_is_saved() {
        ScheduleResponse created = scheduleService.create(new ScheduleRequest(
                "len-edge", "가".repeat(Schedule.NAME_MAX), null, "0 0 2 * * ?",
                null, null, "설".repeat(Schedule.DESCRIPTION_MAX), null, null, null, null));

        Schedule saved = scheduleRepository.findById(created.id()).orElseThrow();
        assertEquals(Schedule.NAME_MAX, saved.getName().length());
        assertEquals(Schedule.DESCRIPTION_MAX, saved.getDescription().length());
    }

    @DisplayName("설명이 컬럼 길이를 넘으면 400 — 검증 없이 대입되던 자리다")
    @Test
    void description_over_column_length_400() {
        BusinessException e = assertThrows(BusinessException.class,
                () -> scheduleService.create(
                        request("len-desc", "이름", null, "설".repeat(Schedule.DESCRIPTION_MAX + 1))));

        assertEquals(HttpStatus.BAD_REQUEST, e.getStatus());
        assertTrue(e.getMessage().contains(String.valueOf(Schedule.DESCRIPTION_MAX)), e.getMessage());
    }

    /**
     * 공백만 있는 엔진 이름은 {@code validateEngine} 의 {@code isBlank()} 를 그대로 통과해 저장됐다.
     * PostgreSQL 이 {@code varchar} 의 뒤쪽 공백을 잘라 받아 오류도 나지 않았다(실측: 200 으로 생성됨).
     * 공백을 미지정(null)으로 정규화해 실행 시점의 "공백 = 미지정" 규약과 결과를 맞춘다.
     */
    @DisplayName("공백만 있는 엔진·설명은 미지정으로 정규화된다 — 공백이 그대로 저장되지 않는다")
    @Test
    void blank_engine_and_description_become_null() {
        ScheduleResponse created = scheduleService.create(
                new ScheduleRequest("len-blank", "이름", null, "0 0 2 * * ?",
                        null, null, "   ", " ".repeat(40), null, null, null));

        Schedule saved = scheduleRepository.findById(created.id()).orElseThrow();
        assertNull(saved.getEngine(), "공백이 그대로 저장되면 컬럼 길이를 넘거나 뜻 없는 값이 남는다");
        assertNull(saved.getDescription());
    }

    @DisplayName("수정 경로에도 같은 길이 상한이 걸린다")
    @Test
    void update_applies_same_limits() {
        ScheduleResponse created = scheduleService.create(request("len-upd", "이름", null, null));

        assertEquals(HttpStatus.BAD_REQUEST, assertThrows(BusinessException.class,
                () -> scheduleService.update(created.id(),
                        new ScheduleUpdateRequest(null, "가".repeat(Schedule.NAME_MAX + 1), null, null,
                                null, null, null, null, null, null, null, null))).getStatus());
    }

    private static ScheduleRequest request(String id, String name, String cron, String description) {
        return new ScheduleRequest(id, name, null, cron == null ? "0 0 2 * * ?" : cron,
                null, null, description, null, null, null, null);
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
        ScheduleUpdateRequest renamed = new ScheduleUpdateRequest("changed", "이름", null, null,
                null, null, null, null, null, null, null, null);

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
                new ScheduleUpdateRequest(null, null, null, null, null, true, null, null, null, null, null, null));

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

    @DisplayName("같은 사용 여부로 정렬해도 한 건씩 페이지를 넘기면 id 내림차순으로 겹침·빠짐 없이 이어진다")
    @Test
    void tied_enabled_sort_pages_by_id_desc() {
        List<Long> ids = new ArrayList<>();
        for (int i = 1; i <= 3; i++) ids.add(scheduleService.create(request("tie-" + i, "0 0 2 * * ?")).id());

        List<Long> stitched = new ArrayList<>();
        for (int p = 1; p <= 3; p++) {
            PageResponse<ScheduleResponse> one = scheduleService.list(null, null, null, p, 1, "enabled,desc");
            assertEquals(3, one.totalElements());
            stitched.add(one.items().get(0).id());
        }
        ids.sort(Comparator.reverseOrder());
        assertEquals(ids, stitched, "사용 여부가 전부 true 라 순서는 id 만으로 — 첫 키 방향(내림차순)");
    }

    @DisplayName("검색어의 % 와 _ 는 와일드카드가 아니라 문자 그대로 찾는다")
    @Test
    void keyword_matches_wildcards_literally() {
        scheduleService.create(request("k1", "a_b", null, null));
        scheduleService.create(request("k2", "100%", null, null));
        scheduleService.create(request("k3", "axb", null, null));

        assertEquals(1, scheduleService.list("a_b", null, null, 1, 10, null).totalElements(), "_ 가 와일드카드면 axb 까지 2");
        assertEquals(1, scheduleService.list("100%", null, null, 1, 10, null).totalElements());
        assertEquals(0, scheduleService.list("a%b", null, null, 1, 10, null).totalElements(), "% 가 와일드카드면 a_b·axb 2");
        assertEquals(1, scheduleService.list(" A_B ", null, null, 1, 10, null).totalElements(), "앞뒤 공백 제거·대소문자 무시");
    }

    @DisplayName("resourceType=VM 은 종류를 비워 둔 스케줄도 포함하고 다른 종류는 제외한다")
    @Test
    void vm_filter_includes_null_type() {
        scheduleService.create(request("t-null", "종류 없음", null, null));   // resourceType = null
        scheduleService.create(new ScheduleRequest("t-vm", "브이엠", Workload.ResourceType.VM,
                "0 0 2 * * ?", null, null, null, null, null, null, null));
        scheduleService.create(new ScheduleRequest("t-gpu", "지피유", Workload.ResourceType.GPU,
                "0 0 2 * * ?", null, null, null, null, null, null, null));

        PageResponse<ScheduleResponse> vm = scheduleService.list(null, Workload.ResourceType.VM, null, 1, 10, "scheduleId,asc");
        assertEquals(List.of("t-null", "t-vm"), vm.items().stream().map(ScheduleResponse::scheduleId).toList(),
                "실행이 VM 으로 해석하는 종류 빈 스케줄이 목록 VM 필터에도 든다");
        assertNull(vm.items().get(0).resourceType(), "응답은 원값(null) 그대로");
        assertEquals(1, scheduleService.list(null, Workload.ResourceType.GPU, null, 1, 10, null).totalElements());
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
