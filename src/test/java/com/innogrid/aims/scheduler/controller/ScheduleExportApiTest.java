package com.innogrid.aims.scheduler.controller;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.support.TransactionTemplate;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import com.innogrid.aims.scheduler.entity.Schedule;
import com.innogrid.aims.scheduler.entity.ScheduleRun;
import com.innogrid.aims.scheduler.repository.ScheduleRunRepository;

import jakarta.persistence.EntityManager;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Excel 내보내기({@code GET /schedules/export} · {@code GET /schedules/{id}/runs/export}).
 *
 * <p>파일의 행이 같은 파라미터로 목록 · 실행 이력의 모든 쪽을 이어 붙여 화면 표기로 바꾼 것과 같은지, 셀 표기 · 수식 · 특수 문자,
 * 오류 응답이 JSON 인지, 헤더와 zip 부품을 본다. 파일은 JDK 의 {@link ZipInputStream} 과 DOM 파서로 읽는다.</p>
 *
 * <p>이 컨텍스트는 실 게이트웨이라 즉시 실행으로는 실행 이력이 생기지 않으므로 행을 저장소에 직접 심고, 시각은 JPQL 일괄 갱신으로
 * 고정한다({@link ScheduleRunListFilterApiTest} 와 같은 방식). 업무 키는 숫자만 붙여 검색어(영문자)와 겹치지 않게 한다.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@WithMockUser
class ScheduleExportApiTest {

    private static final String BASE = "/api/v1/scheduler/schedules";
    private static final String XLSX = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
    private static final String MAIN_NS = "http://schemas.openxmlformats.org/spreadsheetml/2006/main";
    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private static final DateTimeFormatter LOCAL = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final List<String> SCHEDULE_HEADER = List.of(
            "스케줄 ID", "스케줄명", "리소스 종류", "실행 주기", "설명", "자동 실행 여부", "사용 여부", "시작 시간", "종료 시간", "처리 상태");
    private static final List<String> RUN_HEADER = List.of("스케줄 ID", "시작 시간", "종료 시간", "처리 상태", "메시지");
    private static final String CRON = "0 0 2 * * ?";
    /** UTC 15:30 대 — 서울로 바꾸면 날짜가 다음 날로 넘어간다. */
    private static final OffsetDateTime FIXED_START = OffsetDateTime.parse("2026-03-01T15:30:45Z");
    private static final OffsetDateTime FIXED_END = OffsetDateTime.parse("2026-03-01T15:31:05Z");

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired ScheduleRunRepository runRepository;
    @Autowired EntityManager em;
    @Autowired TransactionTemplate tx;

    @DisplayName("실행 이력 내보내기의 행은 같은 파라미터로 실행 이력 쪽을 모두 이어 붙여 화면 표기로 바꾼 것과 같다")
    @Test
    void runs_export_rows_equal_stitched_run_pages() throws Exception {
        Runs s = seedRuns();
        try {
            List<List<String>> byMessage = assertRunExportMatchesPages(s, "keyword", " A ", "sort", "message,asc");
            assertEquals(List.of("alpha fail", "bravo fail", "charlie ok", "delta ok"), column(byMessage, 4),
                    "검색어로 넷만 · 메시지 값 순서(id 순서 delta · alpha · charlie · bravo 와 다름)");
            assertEquals(2, assertRunExportMatchesPages(s, "status", "COMPLETED", "triggerType", "MANUAL", "sort", "startedAt,asc").size());
            assertEquals(3, assertRunExportMatchesPages(s, "from", s.base().minusHours(3).toString(), "to", s.base().toString(),
                    "sort", "status,desc").size(), "[T−3h, T) 세 행 · 상태 내림차순 · 같은 상태는 id 내림차순");
            assertEquals(6, assertRunExportMatchesPages(s).size(), "파라미터가 없으면 전부 · 기본 정렬 startedAt,desc");
        } finally {
            clean(s.scheduleId(), s.runIds());
        }
    }

    @DisplayName("실행 이력 셀은 화면 표기다 — 서울 시각 · 실행 중 종료 시간과 빈 메시지는 - · 처리 상태는 Completed · Failed · Running")
    @Test
    void run_cells_use_screen_notation() throws Exception {
        Runs s = seedRuns();
        try {
            long completed = s.runIds().get(2);
            tx.executeWithoutResult(t -> em.createQuery(
                            "update ScheduleRun r set r.startedAt = :s, r.finishedAt = :f, r.message = :m where r.id = :id")
                    .setParameter("s", FIXED_START).setParameter("f", FIXED_END).setParameter("m", "  charlie ok  ")
                    .setParameter("id", completed).executeUpdate());
            setMessage(s.runIds().get(3), "   ");

            List<List<String>> rows = dataRows(export(BASE + "/" + s.scheduleId() + "/runs/export", "sort", "startedAt,asc"), RUN_HEADER);
            assertEquals(6, rows.size());
            assertEquals(List.of(s.key(), "2026-03-02 00:30:45", "2026-03-02 00:31:05", "Completed", "charlie ok"), rows.get(0),
                    "UTC 15:30 대는 서울 다음 날 00:30 대 · 메시지는 앞뒤 공백을 지움");
            assertEquals(List.of(s.key(), s.base().atZoneSameInstant(SEOUL).format(LOCAL), "-", "Running", "-"), rows.get(5),
                    "실행 중 행은 종료 시간과 메시지가 -");
            assertEquals("-", rows.get(3).get(4), "공백뿐인 메시지도 -");
            assertEquals("Failed", rows.get(3).get(3));
            assertEquals(List.of("Completed", "Completed", "Failed", "Failed", "Completed", "Running"), column(rows, 3));
        } finally {
            clean(s.scheduleId(), s.runIds());
        }
    }

    @DisplayName("목록 내보내기의 행은 같은 파라미터로 목록 쪽을 모두 이어 붙인 것과 같고 셀은 화면 표기다")
    @Test
    void list_export_rows_equal_stitched_list_pages() throws Exception {
        Schedules s = seedSchedules();
        try {
            List<List<String>> rows = assertListExportMatchesPages("keyword", s.prefix(), "sort", "name,asc");
            assertEquals(List.of("alpha", "bravo", "charlie", "delta"), column(rows, 1), "이름 순서(id 순서 delta · alpha · charlie · bravo 와 다름)");
            assertEquals(List.of(s.keys().get(1), "alpha", "GPU", CRON, "설명 둘", "예", "아니오",
                    "2026-03-02 00:30:45", "2026-03-02 00:31:05", "Completed"), rows.get(0), "서울 시각 · 자동 실행 예 · 사용 아니오");
            assertEquals(List.of(s.keys().get(3), "bravo", "NPU", CRON, "-", "아니오", "예"), rows.get(1).subList(0, 7), "공백뿐인 설명은 -");
            assertEquals("Failed", rows.get(1).get(9));
            assertEquals(List.of("VM", "Running"), List.of(rows.get(2).get(2), rows.get(2).get(9)));
            assertEquals("-", rows.get(2).get(8), "실행 중이면 종료 시간 -");
            assertEquals(List.of(s.keys().get(0), "delta", "-", CRON, "-", "아니오", "예", "-", "-", "Pending"), rows.get(3),
                    "종류 없음 · 설명 없음 · 실행 전은 - 와 Pending");
            assertEquals(4, assertListExportMatchesPages("keyword", s.prefix()).size(), "기본 정렬 createdAt,desc");
        } finally {
            for (long id : s.ids()) mockMvc.perform(delete(BASE).param("ids", String.valueOf(id)));
        }
    }

    @DisplayName("목록 내보내기는 리소스 종류 · 사용 여부 · 검색어 필터를 목록과 같은 규칙으로 겹친다")
    @Test
    void list_export_filters_follow_list_rules() throws Exception {
        Schedules s = seedSchedules();
        try {
            List<List<String>> vm = assertListExportMatchesPages("keyword", s.prefix(), "resourceType", "VM", "sort", "scheduleId,asc");
            assertEquals(List.of(s.keys().get(0), s.keys().get(2)), column(vm, 0), "VM 은 종류를 비워 둔 스케줄도 포함");
            assertEquals(1, assertListExportMatchesPages("keyword", s.prefix(), "enabled", "false").size());
            assertEquals(0, assertListExportMatchesPages("keyword", s.prefix(), "resourceType", "GPU", "enabled", "true").size());
            assertEquals(4, assertListExportMatchesPages("keyword", "  " + s.prefix().toUpperCase() + " ", "sort", "enabled,desc").size(),
                    "앞뒤 공백 제거 · 대소문자 무시");
        } finally {
            for (long id : s.ids()) mockMvc.perform(delete(BASE).param("ids", String.valueOf(id)));
        }
    }

    @DisplayName("= · + · @ 로 시작하는 값과 XML 특수 문자는 수식 없이 글자 그대로 남고 XML 이 허용하지 않는 제어 문자만 빠진다")
    @Test
    void formula_like_values_stay_text_and_control_chars_are_dropped() throws Exception {
        String key = "exf-" + System.nanoTime();
        long scheduleId = createSchedule(key, "=1+1", "@SUM(A1)", "VM", true, true);
        String[] messages = { "=HYPERLINK(\"http://x\")", "+cmd|' /C calc'!A0", "-10", "a<b&c>\"d\"'e'\u0001\u0008f\tg" };
        List<Long> runIds = new ArrayList<>();
        try {
            OffsetDateTime base = OffsetDateTime.now(ZoneOffset.UTC).minusMinutes(10).truncatedTo(ChronoUnit.SECONDS);
            for (int i = 0; i < messages.length; i++) {
                ScheduleRun run = new ScheduleRun(scheduleId, key, ScheduleRun.TriggerType.MANUAL, "tester");
                run.finish(ScheduleRun.RunStatus.COMPLETED, messages[i]);
                runIds.add(runRepository.save(run).getId());
                backdate(runIds.get(i), base.minusHours(messages.length - i));
            }

            byte[] runsFile = export(BASE + "/" + scheduleId + "/runs/export", "sort", "startedAt,asc");
            assertEquals(List.of("=HYPERLINK(\"http://x\")", "+cmd|' /C calc'!A0", "-10", "a<b&c>\"d\"'e'f\tg"),
                    column(dataRows(runsFile, RUN_HEADER), 4), "제어 문자 \\u0001 · \\u0008 만 빠지고 탭은 남음");
            byte[] listFile = export(BASE + "/export", "keyword", key);
            assertEquals(List.of(List.of(key, "=1+1", "VM", CRON, "@SUM(A1)", "예", "예", "-", "-", "Pending")),
                    dataRows(listFile, SCHEDULE_HEADER));
            for (byte[] file : List.of(runsFile, listFile)) {
                String sheet = new String(unzip(file).get("xl/worksheets/sheet1.xml"), java.nio.charset.StandardCharsets.UTF_8);
                assertFalse(sheet.contains("<f>") || sheet.contains("<f "), "수식 셀 없음");
                assertFalse(sheet.contains("\u0001") || sheet.contains("\u0008"), "제어 문자 없음");
                assertEquals(count(sheet, "<c "), count(sheet, "<c r=\"[A-Z]+\\d+\" t=\"inlineStr\""), "모든 셀이 인라인 문자열");
            }
        } finally {
            clean(scheduleId, runIds);
        }
    }

    @DisplayName("내보내기 오류는 목록 · 실행 이력과 같은 JSON 이다 — 같은 경로의 200 xlsx 를 대조군으로 함께 본다")
    @Test
    void export_errors_stay_json() throws Exception {
        Runs s = seedRuns();
        try {
            String runs = BASE + "/" + s.scheduleId() + "/runs/export";
            String browserAccept = "application/json, text/plain, */*";
            mockMvc.perform(get(runs).header(HttpHeaders.ACCEPT, browserAccept))
                    .andExpect(status().isOk()).andExpect(content().contentType(XLSX));
            mockMvc.perform(get(BASE + "/export").param("keyword", s.key()))
                    .andExpect(status().isOk()).andExpect(content().contentType(XLSX));

            mockMvc.perform(get(runs).param("sort", "engineId,asc").header(HttpHeaders.ACCEPT, browserAccept))
                    .andExpect(status().isBadRequest()).andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                    .andExpect(jsonPath("$.message", containsString("정렬할 수 없는 필드입니다: engineId")))
                    .andExpect(jsonPath("$.message", containsString("scheduleKey")));
            mockMvc.perform(get(BASE + "/export").param("sort", "createdBy,asc"))
                    .andExpect(status().isBadRequest()).andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                    .andExpect(jsonPath("$.message", containsString("정렬할 수 없는 필드입니다: createdBy")));
            mockMvc.perform(get(runs).param("from", "2026-09-02T00:00:00Z").param("to", "2026-09-01T00:00:00Z"))
                    .andExpect(status().isBadRequest()).andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                    .andExpect(jsonPath("$.message", containsString("시작이 끝보다 뒤")));
            mockMvc.perform(get(runs).param("status", "nope"))
                    .andExpect(status().isBadRequest()).andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                    .andExpect(jsonPath("$.message", containsString("RUNNING, COMPLETED, FAILED")));
            mockMvc.perform(get(BASE + "/export").param("resourceType", "TPU"))
                    .andExpect(status().isBadRequest()).andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON));
            mockMvc.perform(get(BASE + "/999999999/runs/export").header(HttpHeaders.ACCEPT, browserAccept))
                    .andExpect(status().isNotFound()).andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                    .andExpect(jsonPath("$.message").isNotEmpty());
        } finally {
            clean(s.scheduleId(), s.runIds());
        }
    }

    @DisplayName("응답은 xlsx 첨부 파일이고 파일 이름에 서울 시각이 붙으며 zip 부품 여섯 개와 굵은 머리글 한 줄을 담는다")
    @Test
    void response_headers_and_zip_parts() throws Exception {
        Runs s = seedRuns();
        try {
            assertFile(mockMvc.perform(get(BASE + "/export").param("keyword", s.key())).andExpect(status().isOk()).andReturn().getResponse(),
                    "schedules-", "스케줄", SCHEDULE_HEADER, 1);
            assertFile(mockMvc.perform(get(BASE + "/" + s.scheduleId() + "/runs/export")).andExpect(status().isOk()).andReturn().getResponse(),
                    "schedule-runs-" + s.key() + "-", "실행 이력", RUN_HEADER, 6);
        } finally {
            clean(s.scheduleId(), s.runIds());
        }
    }

    // ── 확인 도우미 ─────────────────────────────────────────────

    private void assertFile(MockHttpServletResponse response, String filePrefix, String sheetName, List<String> header,
                            int dataRows) throws Exception {
        assertEquals(XLSX, response.getContentType());
        String disposition = response.getHeader(HttpHeaders.CONTENT_DISPOSITION);
        Matcher m = Pattern.compile("attachment; filename=\"" + Pattern.quote(filePrefix) + "(\\d{8}-\\d{6})\\.xlsx\"")
                .matcher(String.valueOf(disposition));
        assertTrue(m.matches(), disposition);
        LocalDateTime stamp = LocalDateTime.parse(m.group(1), DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
        assertTrue(Duration.between(stamp, LocalDateTime.now(SEOUL)).abs().toMinutes() < 5, "파일 이름 시각은 서울 기준: " + stamp);

        Map<String, byte[]> parts = unzip(response.getContentAsByteArray());
        assertEquals(List.of("[Content_Types].xml", "_rels/.rels", "xl/workbook.xml", "xl/_rels/workbook.xml.rels",
                "xl/styles.xml", "xl/worksheets/sheet1.xml"), List.copyOf(parts.keySet()));
        String types = new String(parts.get("[Content_Types].xml"), java.nio.charset.StandardCharsets.UTF_8);
        for (String part : List.of("/xl/workbook.xml", "/xl/worksheets/sheet1.xml", "/xl/styles.xml")) {
            assertTrue(types.contains("PartName=\"" + part + "\""), part);
        }
        Element sheet = (Element) parse(parts.get("xl/workbook.xml")).getElementsByTagNameNS(MAIN_NS, "sheet").item(0);
        assertEquals(sheetName, sheet.getAttribute("name"));

        NodeList rows = parse(parts.get("xl/worksheets/sheet1.xml")).getElementsByTagNameNS(MAIN_NS, "row");
        assertEquals(1 + dataRows, rows.getLength());
        NodeList headCells = ((Element) rows.item(0)).getElementsByTagNameNS(MAIN_NS, "c");
        for (int i = 0; i < headCells.getLength(); i++) {
            assertEquals("1", ((Element) headCells.item(i)).getAttribute("s"), "머리글은 굵은 글꼴 서식");
        }
        if (dataRows > 0) {
            assertEquals("", ((Element) ((Element) rows.item(1)).getElementsByTagNameNS(MAIN_NS, "c").item(0)).getAttribute("s"),
                    "자료 행은 기본 서식");
        }
        assertEquals(header, cells(parts.get("xl/worksheets/sheet1.xml")).get(0));
    }

    /** 실행 이력 쪽(크기 2)을 모두 이어 붙여 화면 표기로 바꾼 것과 내보낸 파일의 자료 행이 같은지 보고 그 행을 돌려준다. */
    private List<List<String>> assertRunExportMatchesPages(Runs s, String... pairs) throws Exception {
        List<List<String>> expected = new ArrayList<>();
        for (JsonNode run : stitch(BASE + "/" + s.scheduleId() + "/runs", pairs)) {
            expected.add(List.of(text(run.get("scheduleKey")), time(run.get("startedAt")), time(run.get("finishedAt")),
                    statusLabel(run.get("status")), text(run.get("message"))));
        }
        List<List<String>> actual = dataRows(export(BASE + "/" + s.scheduleId() + "/runs/export", pairs), RUN_HEADER);
        assertEquals(expected, actual, "실행 이력 쪽을 이어 붙인 행 = 파일 행");
        return actual;
    }

    /** 목록 쪽(크기 2)을 모두 이어 붙여 화면 표기로 바꾼 것과 내보낸 파일의 자료 행이 같은지 보고 그 행을 돌려준다. */
    private List<List<String>> assertListExportMatchesPages(String... pairs) throws Exception {
        List<List<String>> expected = new ArrayList<>();
        for (JsonNode row : stitch(BASE, pairs)) {
            expected.add(List.of(text(row.get("scheduleId")), text(row.get("name")), text(row.get("resourceType")),
                    text(row.get("cronExpression")), text(row.get("description")),
                    row.get("autoRun").asBoolean() ? "예" : "아니오", row.get("enabled").asBoolean() ? "예" : "아니오",
                    time(row.get("lastRunAt")), time(row.get("lastRunFinishedAt")), statusLabel(row.get("lastRunStatus"))));
        }
        List<List<String>> actual = dataRows(export(BASE + "/export", pairs), SCHEDULE_HEADER);
        assertEquals(expected, actual, "목록 쪽을 이어 붙인 행 = 파일 행");
        return actual;
    }

    /** 크기 2 로 1쪽부터 총건수만큼 이어 붙인다. */
    private List<JsonNode> stitch(String path, String... pairs) throws Exception {
        List<JsonNode> out = new ArrayList<>();
        long total = -1;
        for (int p = 1; total < 0 || out.size() < total; p++) {
            MockHttpServletRequestBuilder req = get(path).param("size", "2").param("page", String.valueOf(p));
            for (int i = 0; i + 1 < pairs.length; i += 2) req = req.param(pairs[i], pairs[i + 1]);
            JsonNode data = objectMapper.readTree(mockMvc.perform(req).andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString()).get("data");
            total = data.get("totalElements").asLong();
            if (data.get("items").isEmpty()) break;
            for (JsonNode item : data.get("items")) out.add(item);
        }
        assertEquals(total, out.size(), "이어 붙인 행 수 = 총건수");
        return out;
    }

    private byte[] export(String path, String... pairs) throws Exception {
        MockHttpServletRequestBuilder req = get(path);
        for (int i = 0; i + 1 < pairs.length; i += 2) req = req.param(pairs[i], pairs[i + 1]);
        return mockMvc.perform(req).andExpect(status().isOk()).andExpect(content().contentType(XLSX))
                .andReturn().getResponse().getContentAsByteArray();
    }

    // ── 화면 표기(프론트 schedulerPresentation · schedulerMapper 와 같은 규칙) ──

    private static String text(JsonNode v) {
        if (v == null || v.isNull()) return "-";
        String trimmed = v.asString().strip();
        return trimmed.isEmpty() ? "-" : trimmed;
    }

    private static String time(JsonNode v) {
        return v == null || v.isNull() ? "-" : OffsetDateTime.parse(v.asString()).atZoneSameInstant(SEOUL).format(LOCAL);
    }

    private static String statusLabel(JsonNode v) {
        if (v == null || v.isNull()) return "Pending";
        return switch (v.asString()) {
            case "COMPLETED" -> "Completed";
            case "RUNNING" -> "Running";
            case "FAILED" -> "Failed";
            default -> throw new AssertionError("모르는 상태: " + v);
        };
    }

    // ── xlsx 읽기 ──────────────────────────────────────────────

    private static Map<String, byte[]> unzip(byte[] file) throws IOException {
        Map<String, byte[]> parts = new LinkedHashMap<>();
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(file))) {
            for (ZipEntry e = zip.getNextEntry(); e != null; e = zip.getNextEntry()) parts.put(e.getName(), zip.readAllBytes());
        }
        return parts;
    }

    private static Document parse(byte[] xml) throws Exception {
        DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
        f.setNamespaceAware(true);
        f.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        return f.newDocumentBuilder().parse(new ByteArrayInputStream(xml));
    }

    /** 시트의 모든 행(머리글 포함) — 셀 참조가 A1 부터 빈틈없이 이어지는지도 본다. */
    private static List<List<String>> cells(byte[] sheetXml) throws Exception {
        NodeList rows = parse(sheetXml).getElementsByTagNameNS(MAIN_NS, "row");
        List<List<String>> out = new ArrayList<>();
        for (int r = 0; r < rows.getLength(); r++) {
            Element row = (Element) rows.item(r);
            assertEquals(String.valueOf(r + 1), row.getAttribute("r"));
            NodeList cs = row.getElementsByTagNameNS(MAIN_NS, "c");
            List<String> values = new ArrayList<>();
            for (int c = 0; c < cs.getLength(); c++) {
                Element cell = (Element) cs.item(c);
                assertEquals(String.valueOf((char) ('A' + c)) + (r + 1), cell.getAttribute("r"));
                assertEquals("inlineStr", cell.getAttribute("t"));
                values.add(cell.getElementsByTagNameNS(MAIN_NS, "t").item(0).getTextContent());
            }
            out.add(values);
        }
        return out;
    }

    private static List<List<String>> dataRows(byte[] file, List<String> header) throws Exception {
        List<List<String>> all = cells(unzip(file).get("xl/worksheets/sheet1.xml"));
        assertEquals(header, all.get(0), "머리글 = 화면 열");
        return all.subList(1, all.size());
    }

    private static List<String> column(List<List<String>> rows, int index) {
        return rows.stream().map(r -> r.get(index)).toList();
    }

    private static int count(String text, String regex) {
        Matcher m = Pattern.compile(regex).matcher(text);
        int n = 0;
        while (m.find()) n++;
        return n;
    }

    // ── 시딩 ───────────────────────────────────────────────────

    private record Runs(long scheduleId, String key, List<Long> runIds, OffsetDateTime base) { }

    private record Schedules(String prefix, List<String> keys, List<Long> ids) { }

    /**
     * 스케줄 1 · 실행 6 — 시작 시각은 T−5h … T 로 한 시간 간격(T 는 초 단위 고정). 업무 키는 {@code exr-<숫자>}.
     * 메시지에 a 가 든 넷의 값 순서(alpha · bravo · charlie · delta)는 id 순서(delta · alpha · charlie · bravo)와 일부러 다르다.
     * <pre>
     *   T−5h COMPLETED MANUAL "delta ok"     T−4h FAILED AUTO "alpha fail"     T−3h COMPLETED AUTO "charlie ok"
     *   T−2h FAILED MANUAL "bravo fail"      T−1h COMPLETED MANUAL "echo ok"   T    RUNNING MANUAL (메시지 없음)
     * </pre>
     */
    private Runs seedRuns() throws Exception {
        ScheduleRun.RunStatus[] statuses = {
                ScheduleRun.RunStatus.COMPLETED, ScheduleRun.RunStatus.FAILED, ScheduleRun.RunStatus.COMPLETED,
                ScheduleRun.RunStatus.FAILED, ScheduleRun.RunStatus.COMPLETED, ScheduleRun.RunStatus.RUNNING };
        ScheduleRun.TriggerType[] triggers = {
                ScheduleRun.TriggerType.MANUAL, ScheduleRun.TriggerType.AUTO, ScheduleRun.TriggerType.AUTO,
                ScheduleRun.TriggerType.MANUAL, ScheduleRun.TriggerType.MANUAL, ScheduleRun.TriggerType.MANUAL };
        String[] messages = { "delta ok", "alpha fail", "charlie ok", "bravo fail", "echo ok", null };
        String key = "exr-" + System.nanoTime();
        long scheduleId = createSchedule(key, key, null, "GPU", false, true);
        List<Long> runIds = new ArrayList<>();
        OffsetDateTime base = OffsetDateTime.now(ZoneOffset.UTC).minusMinutes(10).truncatedTo(ChronoUnit.SECONDS);
        try {
            for (int i = 0; i < 6; i++) {
                ScheduleRun run = new ScheduleRun(scheduleId, key, triggers[i], "tester");
                if (statuses[i] != ScheduleRun.RunStatus.RUNNING) run.finish(statuses[i], messages[i]);
                long id = runRepository.save(run).getId();
                runIds.add(id);
                backdate(id, base.minusHours(5 - i));
            }
            return new Runs(scheduleId, key, runIds, base);
        } catch (Throwable e) {
            clean(scheduleId, runIds);
            throw e;
        }
    }

    /**
     * 스케줄 4 — 업무 키는 {@code exs<숫자>-1} … {@code -4}, 이름 순서(alpha · bravo · charlie · delta)는 id 순서와 일부러 다르다.
     * <pre>
     *   -1 delta   종류 없음 · 설명 없음 · 자동 아니오 · 사용 예   · 실행 전
     *   -2 alpha   GPU · "  설명 둘  " · 자동 예 · 사용 아니오     · COMPLETED (고정 시각)
     *   -3 charlie VM  · "설명 셋" · 자동 아니오 · 사용 예          · RUNNING
     *   -4 bravo   NPU · 공백뿐인 설명 · 자동 아니오 · 사용 예      · FAILED
     * </pre>
     */
    private Schedules seedSchedules() throws Exception {
        String prefix = "exs" + System.nanoTime();
        List<String> keys = List.of(prefix + "-1", prefix + "-2", prefix + "-3", prefix + "-4");
        List<Long> ids = new ArrayList<>();
        ids.add(createSchedule(keys.get(0), "delta", null, null, false, true));
        ids.add(createSchedule(keys.get(1), "alpha", "  설명 둘  ", "GPU", true, false));
        ids.add(createSchedule(keys.get(2), "charlie", "설명 셋", "VM", false, true));
        ids.add(createSchedule(keys.get(3), "bravo", "   ", "NPU", false, true));
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.SECONDS);
        tx.executeWithoutResult(t -> {
            em.find(Schedule.class, ids.get(1)).recordRun(ScheduleRun.RunStatus.COMPLETED, FIXED_START, FIXED_END, "ok");
            em.find(Schedule.class, ids.get(2)).recordRun(ScheduleRun.RunStatus.RUNNING, now.minusMinutes(1), null, null);
            em.find(Schedule.class, ids.get(3)).recordRun(ScheduleRun.RunStatus.FAILED, now.minusMinutes(3), now.minusMinutes(2), "x");
        });
        return new Schedules(prefix, keys, ids);
    }

    private long createSchedule(String key, String name, String description, String resourceType,
                                boolean autoRun, boolean enabled) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("scheduleId", key);
        body.put("name", name);
        body.put("cronExpression", CRON);
        body.put("autoRun", autoRun);
        body.put("enabled", enabled);
        if (resourceType != null) body.put("resourceType", resourceType);
        if (description != null) body.put("description", description);
        JsonNode created = objectMapper.readTree(mockMvc.perform(post(BASE).contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).get("data");
        assertNotEquals(0, created.get("id").asLong());
        return created.get("id").asLong();
    }

    /** 생성자가 채운 시작 시각을 덮어쓴다 — 저장 경로로는 만들 수 없는 과거 시각. */
    private void backdate(long runId, OffsetDateTime at) {
        tx.executeWithoutResult(t -> em.createQuery("update ScheduleRun r set r.startedAt = :t where r.id = :id")
                .setParameter("t", at).setParameter("id", runId).executeUpdate());
    }

    private void setMessage(long runId, String message) {
        tx.executeWithoutResult(t -> em.createQuery("update ScheduleRun r set r.message = :m where r.id = :id")
                .setParameter("m", message).setParameter("id", runId).executeUpdate());
    }

    /** 심은 실행 행을 지우고 스케줄을 지운다. 지우기는 확인하지 않는다. */
    private void clean(long scheduleId, List<Long> runIds) throws Exception {
        runRepository.deleteAllById(runIds);
        mockMvc.perform(delete(BASE).param("ids", String.valueOf(scheduleId)));
    }
}
