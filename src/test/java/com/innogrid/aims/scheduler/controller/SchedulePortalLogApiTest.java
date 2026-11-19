package com.innogrid.aims.scheduler.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.util.ClassUtils;
import org.springframework.web.bind.annotation.RestController;

import com.innogrid.aims.common.log.PortalLog;
import com.innogrid.aims.monitoring.dto.log.PortalLogResult;
import com.innogrid.aims.monitoring.dto.log.PortalLogType;
import com.innogrid.aims.monitoring.entity.PortalLogEntry;
import com.innogrid.aims.monitoring.log.RequestIdFilter;
import com.innogrid.aims.monitoring.repository.PortalLogRepository;
import com.innogrid.aims.scheduler.dto.ScheduleRequest;
import com.innogrid.aims.scheduler.entity.Workload;
import com.innogrid.aims.scheduler.repository.ScheduleRepository;
import com.innogrid.aims.scheduler.repository.WorkloadRepository;
import com.innogrid.aims.scheduler.service.NodeResourceCollector;
import com.innogrid.aims.scheduler.service.ScheduleService;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 스케줄 화면 API의 포털 로그 — 붙인 열 곳(목록·상세·실행 이력·리소스 종류 조회와 목록·실행 이력 내보내기 ACCESS, 생성·수정·삭제 AUDIT,
 * 즉시 실행 ACTION)이
 * 유형·작업·내용·결과·연결을 그대로 남기고, 미리보기·모의 실행·실행 주기 검증·실행 상세·실행 재생은 남기지 않는지 본다.
 *
 * <p>행은 요청마다 단 {@code X-Request-Id}로 고른다. 공유 H2에 다른 테스트가 남긴 행이나 요청 ID가 없는 SYSTEM 행이 있어도
 * 섞이지 않는다. 형식이 어긋난 요청 ID는 필터가 조용히 UUID로 바꾸므로, 응답 헤더가 보낸 값 그대로인지 먼저 본다.</p>
 *
 * <p>수집기는 빈 스냅샷(노드 0)으로 둔다. 실제 수집기는 메트릭 질의 실패를 SYSTEM 행으로 남기고, 스텁하지 않은 가짜 빈은
 * 대상이 있을 때 null 스냅샷으로 실행을 500으로 끝낸다. 포털 로그 쪽 테스트 지원 클래스는 수집기가 실제 객체라 상속하지 않는다.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@WithMockUser
class SchedulePortalLogApiTest {

    private static final String BASE = "/api/v1/scheduler/schedules";
    private static final String PREFIX = "plog-";
    private static final String SCREEN = "스케줄러";

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired ScheduleService scheduleService;
    @Autowired ScheduleRepository scheduleRepository;
    @Autowired WorkloadRepository workloadRepository;
    @Autowired PortalLogRepository portalLogRepository;
    @MockitoBean NodeResourceCollector collector;

    @BeforeEach
    void stubCollector() {
        when(collector.collect()).thenReturn(new NodeResourceCollector.Snapshot(List.of(), List.of()));
    }

    @AfterEach
    void clean() {
        workloadRepository.findAll().stream().filter(w -> w.getName().startsWith(PREFIX)).forEach(workloadRepository::delete);
        scheduleRepository.findAll().stream().filter(s -> s.getScheduleId().startsWith(PREFIX))
                .forEach(s -> scheduleService.delete(List.of(s.getId())));
        portalLogRepository.deleteAll(portalLogRepository.findAll().stream()
                .filter(l -> l.getRequestId() != null && l.getRequestId().startsWith(PREFIX)).toList());
    }

    @DisplayName("생성·편집·두 건 삭제는 스케줄러 화면의 AUDIT 행 셋으로 남고 생성 행은 새 스케줄 id 를 연결한다")
    @Test
    void create_update_delete_leave_audit_rows_linked_to_schedule() throws Exception {
        String created = send(post(BASE).contentType(MediaType.APPLICATION_JSON)
                .content("{\"scheduleId\":\"plog-t1\",\"name\":\"plog t1\",\"resourceType\":\"NPU\",\"cronExpression\":\"0 0 2 * * ?\"}"),
                "plog-t1-1", 200);
        long id = objectMapper.readTree(created).get("data").get("id").asLong();
        send(put(BASE + "/" + id).contentType(MediaType.APPLICATION_JSON).content("{\"description\":\"plog edit\"}"), "plog-t1-2", 200);
        long other = createViaService("t1b", Workload.ResourceType.NPU, true);
        send(delete(BASE).param("ids", id + "," + other), "plog-t1-3", 200);

        PortalLogEntry create = onlyRow("plog-t1-1");
        PortalLogEntry update = onlyRow("plog-t1-2");
        PortalLogEntry remove = onlyRow("plog-t1-3");
        for (PortalLogEntry row : List.of(create, update, remove)) {
            assertEquals(PortalLogType.AUDIT, row.getType());
            assertEquals(PortalLogResult.SUCCESS, row.getResult());
            assertEquals(SCREEN, row.getScreen());
            assertEquals("user", row.getUserId());
            assertEquals("SCHEDULE", row.getRefType());
        }
        assertEquals("생성", create.getAction());
        assertEquals("스케줄 생성: plog-t1 (plog t1)", create.getContent());
        assertEquals(String.valueOf(id), create.getRefId(), "생성은 응답의 새 스케줄 id 를 연결");
        assertEquals("POST", create.getHttpMethod());
        assertEquals(BASE, create.getRequestUri());
        assertEquals("수정", update.getAction());
        assertEquals("스케줄 수정: plog-t1 (id " + id + ")", update.getContent());
        assertEquals(String.valueOf(id), update.getRefId());
        assertEquals("삭제", remove.getAction());
        assertEquals("스케줄 삭제: 2건 (id " + id + "," + other + ")", remove.getContent());
        assertEquals("[" + id + ", " + other + "]", remove.getRefId(), "알람 정책 다중 삭제와 같은 꼴");
    }

    @DisplayName("같은 스케줄 ID 로 다시 생성하면 409 와 함께 FAILURE 행이 서비스 메시지를 남기고 연결은 비운다")
    @Test
    void duplicate_create_leaves_failure_row_with_service_message() throws Exception {
        createViaService("t2", Workload.ResourceType.NPU, true);
        send(post(BASE).contentType(MediaType.APPLICATION_JSON)
                .content("{\"scheduleId\":\"plog-t2\",\"name\":\"again\",\"cronExpression\":\"0 0 2 * * ?\"}"), "plog-t2-1", 409);

        PortalLogEntry row = onlyRow("plog-t2-1");
        assertEquals(PortalLogType.AUDIT, row.getType());
        assertEquals(PortalLogResult.FAILURE, row.getResult());
        assertEquals("스케줄 생성: plog-t2 (again)", row.getContent());
        assertEquals("이미 있는 스케줄 ID 입니다: plog-t2", row.getDescription());
        assertNull(row.getRefType());
        assertNull(row.getRefId());
    }

    @DisplayName("즉시 실행은 바로 끝나는 ACTION 행 하나로 남고 응답의 실행 id 를 연결하며 내용에 업무 키와 처리 상태를 싣는다")
    @Test
    void run_now_leaves_action_row_linked_to_returned_run() throws Exception {
        long id = createViaService("t3", Workload.ResourceType.NPU, true);
        JsonNode data = objectMapper.readTree(send(post(BASE + "/" + id + "/run"), "plog-t3-1", 200)).get("data");

        PortalLogEntry row = onlyRow("plog-t3-1");
        assertEquals(PortalLogType.ACTION, row.getType());
        assertEquals("실행", row.getAction());
        assertEquals(SCREEN, row.getScreen());
        assertEquals(PortalLogResult.SUCCESS, row.getResult(), "동기 기록이라 진행 중으로 남지 않는다");
        assertNotNull(row.getCompletedAt());
        assertEquals("SCHEDULE_RUN", row.getRefType());
        assertEquals(data.get("runId").asString(), row.getRefId());
        assertEquals("스케줄 즉시 실행: plog-t3 (id " + id + ") · 처리 상태 " + data.get("status").asString(), row.getContent());
    }

    @DisplayName("사용하지 않는 스케줄의 즉시 실행은 409 와 함께 ACTION FAILURE 행으로 남고 실행 연결과 처리 상태는 없다")
    @Test
    void run_now_on_disabled_schedule_leaves_failure_row_without_run_ref() throws Exception {
        long id = createViaService("t4", Workload.ResourceType.NPU, false);
        send(post(BASE + "/" + id + "/run"), "plog-t4-1", 409);

        PortalLogEntry row = onlyRow("plog-t4-1");
        assertEquals(PortalLogType.ACTION, row.getType());
        assertEquals(PortalLogResult.FAILURE, row.getResult());
        assertEquals("스케줄 즉시 실행: id " + id, row.getContent());
        assertEquals("사용하지 않는 스케줄은 실행할 수 없습니다: plog-t4", row.getDescription());
        assertNull(row.getRefId());
    }

    @DisplayName("목록·상세·실행 이력·리소스 종류 조회는 ACCESS 행으로 남고 상세와 실행 이력만 스케줄을 연결한다")
    @Test
    void list_detail_runs_and_resource_types_leave_access_rows() throws Exception {
        long id = createViaService("t5", Workload.ResourceType.NPU, true);
        send(get(BASE).param("keyword", "plog-t5").param("resourceType", "NPU").param("enabled", "true"), "plog-t5-1", 200);
        send(get(BASE + "/" + id), "plog-t5-2", 200);
        send(get(BASE + "/" + id + "/runs").param("status", "FAILED"), "plog-t5-3", 200);
        send(get("/api/v1/scheduler/workloads/resource-types"), "plog-t5-4", 200);

        PortalLogEntry list = onlyRow("plog-t5-1");
        PortalLogEntry detail = onlyRow("plog-t5-2");
        PortalLogEntry runs = onlyRow("plog-t5-3");
        PortalLogEntry types = onlyRow("plog-t5-4");
        for (PortalLogEntry row : List.of(list, detail, runs, types)) {
            assertEquals(PortalLogType.ACCESS, row.getType());
            assertEquals("조회", row.getAction());
            assertEquals(SCREEN, row.getScreen());
            assertEquals(PortalLogResult.SUCCESS, row.getResult());
        }
        assertEquals("스케줄 목록 조회 (키워드: plog-t5, 리소스 종류: NPU, 사용 여부: 사용)", list.getContent());
        assertNull(list.getRefId());
        assertEquals("스케줄 조회: plog-t5 (id " + id + ")", detail.getContent());
        assertEquals("SCHEDULE", detail.getRefType());
        assertEquals(String.valueOf(id), detail.getRefId());
        assertEquals("스케줄 실행 이력 조회: id " + id + " (상태: FAILED, 실행 주체: 전체)", runs.getContent());
        assertEquals(String.valueOf(id), runs.getRefId());
        assertEquals("리소스 종류 목록 조회", types.getContent());
        assertNull(types.getRefId());
    }

    @DisplayName("실행 이력 조회 행은 검색어를 줬을 때만 내용의 괄호 안 끝에 키워드를 붙이고 앞뒤 공백은 지운다")
    @Test
    void runs_access_log_appends_keyword_only_when_given() throws Exception {
        long id = createViaService("t10", Workload.ResourceType.NPU, true);
        send(get(BASE + "/" + id + "/runs").param("keyword", " abc "), "plog-t10-1", 200);
        send(get(BASE + "/" + id + "/runs").param("keyword", "   "), "plog-t10-2", 200);

        assertEquals("스케줄 실행 이력 조회: id " + id + " (상태: 전체, 실행 주체: 전체, 키워드: abc)", onlyRow("plog-t10-1").getContent());
        assertEquals("스케줄 실행 이력 조회: id " + id + " (상태: 전체, 실행 주체: 전체)", onlyRow("plog-t10-2").getContent(),
                "공백뿐인 검색어는 주지 않은 것과 같다");
    }

    @DisplayName("목록 · 실행 이력 Excel 내보내기는 「내보내기」 ACCESS 행으로 남고 실행 이력 쪽만 스케줄을 연결하며 거절은 FAILURE 다")
    @Test
    void exports_leave_access_rows() throws Exception {
        long id = createViaService("t12", Workload.ResourceType.NPU, false);
        send(get(BASE + "/export").param("keyword", "plog-t12").param("resourceType", "NPU").param("enabled", "false"), "plog-t12-1", 200);
        send(get(BASE + "/" + id + "/runs/export").param("status", "FAILED").param("keyword", " abc "), "plog-t12-2", 200);
        send(get(BASE + "/" + id + "/runs/export").param("sort", "engineId,asc"), "plog-t12-3", 400);

        PortalLogEntry list = onlyRow("plog-t12-1");
        PortalLogEntry runs = onlyRow("plog-t12-2");
        PortalLogEntry rejected = onlyRow("plog-t12-3");
        for (PortalLogEntry row : List.of(list, runs, rejected)) {
            assertEquals(PortalLogType.ACCESS, row.getType());
            assertEquals("내보내기", row.getAction());
            assertEquals(SCREEN, row.getScreen());
        }
        assertEquals(PortalLogResult.SUCCESS, list.getResult());
        assertEquals("스케줄 목록 내보내기 (키워드: plog-t12, 리소스 종류: NPU, 사용 여부: 미사용)", list.getContent());
        assertNull(list.getRefId());
        assertEquals(PortalLogResult.SUCCESS, runs.getResult());
        assertEquals("스케줄 실행 이력 내보내기: id " + id + " (상태: FAILED, 실행 주체: 전체, 키워드: abc)", runs.getContent());
        assertEquals("SCHEDULE", runs.getRefType());
        assertEquals(String.valueOf(id), runs.getRefId());
        assertEquals(PortalLogResult.FAILURE, rejected.getResult());
        assertEquals("스케줄 실행 이력 내보내기: id " + id + " (상태: 전체, 실행 주체: 전체)", rejected.getContent());
        assertNotNull(rejected.getDescription());
        assertNull(rejected.getRefType(), "거절된 요청은 연결을 비운다");
    }

    @DisplayName("포털 로그는 스케줄러 컨트롤러 열네 개 가운데 화면이 부르는 열 곳에만 붙어 있다")
    @Test
    void portal_log_is_attached_only_to_screen_endpoints() throws Exception {
        ClassPathScanningCandidateComponentProvider scanner = new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(RestController.class));
        Set<BeanDefinition> controllers = scanner.findCandidateComponents("com.innogrid.aims.scheduler.controller");
        assertEquals(14, controllers.size(), "스케줄러 컨트롤러 수");

        Set<String> attached = new TreeSet<>();
        for (BeanDefinition controller : controllers) {
            Class<?> type = ClassUtils.forName(controller.getBeanClassName(), getClass().getClassLoader());
            for (Method method : type.getDeclaredMethods()) {
                PortalLog log = method.getAnnotation(PortalLog.class);
                if (log != null) {
                    attached.add(type.getSimpleName() + "#" + method.getName() + " " + log.type() + " " + log.action()
                            + " " + log.screen() + " async=" + log.async() + " ref=" + log.refType());
                }
            }
        }
        assertEquals(new TreeSet<>(List.of(
                "ScheduleController#getSchedules ACCESS 조회 스케줄러 async=false ref=",
                "ScheduleController#getSchedule ACCESS 조회 스케줄러 async=false ref=SCHEDULE",
                "ScheduleController#createSchedule AUDIT 생성 스케줄러 async=false ref=SCHEDULE",
                "ScheduleController#updateSchedule AUDIT 수정 스케줄러 async=false ref=SCHEDULE",
                "ScheduleController#deleteSchedules AUDIT 삭제 스케줄러 async=false ref=SCHEDULE",
                "ScheduleController#runNow ACTION 실행 스케줄러 async=false ref=SCHEDULE_RUN",
                "ScheduleController#getRuns ACCESS 조회 스케줄러 async=false ref=SCHEDULE",
                "ScheduleController#exportSchedules ACCESS 내보내기 스케줄러 async=false ref=",
                "ScheduleController#exportRuns ACCESS 내보내기 스케줄러 async=false ref=SCHEDULE",
                "SchedulerController#getResourceTypes ACCESS 조회 스케줄러 async=false ref=")), attached);
    }

    @DisplayName("미리보기·모의 실행·실행 주기 검증·실행 상세·실행 재생은 포털 로그를 남기지 않는다")
    @Test
    void excluded_endpoints_leave_no_rows() throws Exception {
        long id = createViaService("t7", Workload.ResourceType.NPU, true);
        long runId = objectMapper.readTree(send(post(BASE + "/" + id + "/run"), "plog-t7-0", 200)).get("data").get("runId").asLong();
        String draft = "{\"scheduleId\":\"plog-t7d\",\"name\":\"draft\",\"resourceType\":\"NPU\",\"cronExpression\":\"0 0 2 * * ?\"}";

        List<MockHttpServletRequestBuilder> excluded = List.of(
                post(BASE + "/preview").contentType(MediaType.APPLICATION_JSON).content(draft),
                post(BASE + "/dry-run").contentType(MediaType.APPLICATION_JSON).content(draft),
                get(BASE + "/" + id + "/preview"),
                post(BASE + "/" + id + "/dry-run"),
                post(BASE + "/cron/validate").contentType(MediaType.APPLICATION_JSON).content("{\"cronExpression\":\"0 0 2 * * ?\"}"),
                get(BASE + "/" + id + "/runs/" + runId),
                get(BASE + "/" + id + "/runs/" + runId + "/replay"));
        for (int i = 0; i < excluded.size(); i++) {
            String requestId = "plog-t7-" + (i + 1);
            mockMvc.perform(excluded.get(i).header(RequestIdFilter.HEADER, requestId))
                    .andExpect(header().string(RequestIdFilter.HEADER, requestId));
            assertEquals(0, rows(requestId).size(), requestId + " 는 기록 대상이 아니다");
        }
        assertEquals(1, rows("plog-t7-0").size(), "같은 실행의 즉시 실행 행은 남는다(대조군)");
    }

    @DisplayName("본문을 읽지 못한 생성 요청은 400 과 함께 고정 문구의 FAILURE 행 하나로 남는다")
    @Test
    void unreadable_create_body_leaves_fallback_failure_row() throws Exception {
        send(post(BASE).contentType(MediaType.APPLICATION_JSON).content("{broken"), "plog-t8-1", 400);

        PortalLogEntry row = onlyRow("plog-t8-1");
        assertEquals(PortalLogType.AUDIT, row.getType());
        assertEquals(PortalLogResult.FAILURE, row.getResult());
        assertEquals("생성", row.getAction());
        assertEquals("스케줄러 생성 요청 검증 실패", row.getContent());
        assertNull(row.getRefId());
    }

    @DisplayName("실행이 실패로 끝나도 요청이 성공하면 행은 SUCCESS 이고 처리 상태 FAILED 는 내용 끝에 남는다")
    @Test
    void failed_run_still_leaves_success_row_with_failed_status() throws Exception {
        Workload target = new Workload(PREFIX + "t9w", 1, 64);
        target.setResourceType(Workload.ResourceType.CONTAINER);
        workloadRepository.saveAndFlush(target);
        long id = createViaService("t9", Workload.ResourceType.CONTAINER, true);

        JsonNode data = objectMapper.readTree(send(post(BASE + "/" + id + "/run"), "plog-t9-1", 200)).get("data");
        assertEquals("FAILED", data.get("status").asString(), "대상이 있는데 노드가 0 이라 실행은 실패");

        PortalLogEntry row = onlyRow("plog-t9-1");
        assertEquals(PortalLogResult.SUCCESS, row.getResult(), "결과는 요청의 성공 여부");
        assertEquals("스케줄 즉시 실행: plog-t9 (id " + id + ") · 처리 상태 FAILED", row.getContent());
        assertEquals(data.get("runId").asString(), row.getRefId());
    }

    /** 요청 ID를 달아 보내고 응답 헤더가 보낸 값 그대로인지 먼저 본다(형식이 어긋나면 필터가 UUID로 바꿔 행을 못 찾는다) */
    private String send(MockHttpServletRequestBuilder request, String requestId, int expectedStatus) throws Exception {
        return mockMvc.perform(request.header(RequestIdFilter.HEADER, requestId))
                .andExpect(status().is(expectedStatus))
                .andExpect(header().string(RequestIdFilter.HEADER, requestId))
                .andReturn().getResponse().getContentAsString();
    }

    private List<PortalLogEntry> rows(String requestId) {
        return portalLogRepository.findAll().stream().filter(l -> requestId.equals(l.getRequestId())).toList();
    }

    private PortalLogEntry onlyRow(String requestId) {
        List<PortalLogEntry> rows = rows(requestId);
        assertEquals(1, rows.size(), requestId + " 행 수");
        return rows.get(0);
    }

    private long createViaService(String key, Workload.ResourceType type, boolean enabled) {
        return scheduleService.create(new ScheduleRequest(PREFIX + key, PREFIX + key, type, "0 0 2 * * ?", false, enabled,
                null, null, null, null, null)).id();
    }
}
