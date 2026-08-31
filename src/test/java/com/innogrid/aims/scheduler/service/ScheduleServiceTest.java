package com.innogrid.aims.scheduler.service;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
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

    @Test
    void 스케줄을_생성한다() {
        ScheduleResponse created = scheduleService.create(request("nightly", "0 0 2 * * ?"));

        assertNotNull(created.id());
        assertEquals("nightly", created.scheduleId());
        assertEquals(Workload.ResourceType.VM, created.resourceType());
        assertTrue(created.enabled());
        assertFalse(created.autoRun(), "자동 실행은 지정하지 않으면 꺼진 상태다");
    }

    @Test
    void 중복된_스케줄_ID_는_409_이다() {
        scheduleService.create(request("nightly", "0 0 2 * * ?"));

        BusinessException e = assertThrows(BusinessException.class,
                () -> scheduleService.create(request("nightly", "0 0 3 * * ?")));

        assertEquals(HttpStatus.CONFLICT, e.getStatus());
    }

    @Test
    void 잘못된_실행_주기는_400_이다() {
        // 도움말 모달의 7필드 예시
        BusinessException e = assertThrows(BusinessException.class,
                () -> scheduleService.create(request("bad", "0 15 10 ? * 6L 2002-2005")));

        assertEquals(HttpStatus.BAD_REQUEST, e.getStatus());
    }

    @Test
    void 스케줄_ID_형식을_검사한다() {
        assertThrows(BusinessException.class, () -> scheduleService.create(request("한글아이디", "0 0 2 * * ?")));
        assertThrows(BusinessException.class, () -> scheduleService.create(request("_leading", "0 0 2 * * ?")));
        assertDoesNotThrow(() -> scheduleService.create(request("job-1.a_B", "0 0 2 * * ?")));
    }

    @Test
    void 필수값이_비면_400_이다() {
        assertThrows(BusinessException.class, () -> scheduleService.create(
                new ScheduleRequest(null, "이름", null, "0 0 2 * * ?", null, null, null, null, null, null, null)));
        assertThrows(BusinessException.class, () -> scheduleService.create(
                new ScheduleRequest("id", " ", null, "0 0 2 * * ?", null, null, null, null, null, null, null)));
    }

    @Test
    void 알_수_없는_엔진은_400_이다() {
        ScheduleRequest request = new ScheduleRequest("job", "이름", null, "0 0 2 * * ?",
                null, null, null, "quantum", null, null, null);

        assertEquals(HttpStatus.BAD_REQUEST,
                assertThrows(BusinessException.class, () -> scheduleService.create(request)).getStatus());
    }

    @Test
    void 자동_실행이_켜지면_다음_실행_시각이_계산된다() {
        ScheduleResponse created = scheduleService.create(
                new ScheduleRequest("auto", "이름", null, "0 0 2 * * ?", true, true, null,
                        null, null, null, null));

        assertNotNull(created.nextRunAt());
        assertEquals(0, created.nextRunAt().getOffset().getTotalSeconds(), "저장은 UTC");
    }

    @Test
    void 자동_실행이_꺼져_있으면_다음_실행_시각이_없다() {
        assertNull(scheduleService.create(request("manual", "0 0 2 * * ?")).nextRunAt());
    }

    // ── 편집 ───────────────────────────────────────────────────

    @Test
    void 스케줄_ID_는_변경할_수_없다() {
        Long id = scheduleService.create(request("origin", "0 0 2 * * ?")).id();
        ScheduleRequest renamed = new ScheduleRequest("changed", "이름", null, null,
                null, null, null, null, null, null, null);

        assertThrows(BusinessException.class, () -> scheduleService.update(id, renamed));
    }

    @Test
    void 사용_여부를_켜면_다음_실행_시각을_다시_계산한다() {
        // 예전 값을 그대로 두면 이미 지난 시각이라 켜자마자 실행된다
        Long id = scheduleService.create(
                new ScheduleRequest("auto", "이름", null, "0 0 2 * * ?", true, false, null,
                        null, null, null, null)).id();
        assertNull(scheduleRepository.findById(id).orElseThrow().getNextRunAt());

        ScheduleResponse enabled = scheduleService.update(id,
                new ScheduleRequest(null, null, null, null, null, true, null, null, null, null, null));

        assertNotNull(enabled.nextRunAt());
    }

    @Test
    void 없는_스케줄은_404_이다() {
        assertEquals(HttpStatus.NOT_FOUND,
                assertThrows(BusinessException.class, () -> scheduleService.get(99999L)).getStatus());
    }

    // ── 목록 ───────────────────────────────────────────────────

    @Test
    void 목록은_1부터_시작하는_페이지를_쓴다() {
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

    @Test
    void 검색어와_필터가_동작한다() {
        scheduleService.create(new ScheduleRequest("gpu-job", "지피유", Workload.ResourceType.GPU,
                "0 0 2 * * ?", null, null, null, null, null, null, null));
        scheduleService.create(new ScheduleRequest("vm-job", "브이엠", Workload.ResourceType.VM,
                "0 0 2 * * ?", null, null, null, null, null, null, null));

        assertEquals(1, scheduleService.list("gpu", null, null, 1, 10, null).totalElements());
        assertEquals(1, scheduleService.list(null, Workload.ResourceType.VM, null, 1, 10, null).totalElements());
        assertEquals(2, scheduleService.list(null, null, true, 1, 10, null).totalElements());
    }

    @Test
    void 허용하지_않는_정렬_필드는_400_이다() {
        assertEquals(HttpStatus.BAD_REQUEST,
                assertThrows(BusinessException.class,
                        () -> scheduleService.list(null, null, null, 1, 10, "createdBy,asc")).getStatus());
    }

    // ── 삭제 ───────────────────────────────────────────────────

    @Test
    void 여러_건을_한_번에_삭제한다() {
        Long a = scheduleService.create(request("a", "0 0 2 * * ?")).id();
        Long b = scheduleService.create(request("b", "0 0 2 * * ?")).id();

        scheduleService.delete(List.of(a, b, 99999L));   // 없는 ID 는 무시한다

        assertEquals(0, scheduleRepository.count());
    }

    @Test
    void 삭제할_ID_가_없으면_400_이다() {
        assertThrows(BusinessException.class, () -> scheduleService.delete(List.of()));
        assertThrows(BusinessException.class, () -> scheduleService.delete(null));
    }

    // ── 실행 주기 미리보기 ──────────────────────────────────────

    @Test
    void 다음_실행_시각을_요일_이름과_함께_돌려준다() {
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
