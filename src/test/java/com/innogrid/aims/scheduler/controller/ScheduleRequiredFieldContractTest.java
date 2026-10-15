package com.innogrid.aims.scheduler.controller;

import java.lang.reflect.RecordComponent;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import com.innogrid.aims.scheduler.dto.CronPreviewResponse;
import com.innogrid.aims.scheduler.dto.ScheduleRequest;
import com.innogrid.aims.scheduler.dto.ScheduleRunResponse;
import com.innogrid.aims.scheduler.engine.SchedulingEngine;
import io.swagger.v3.oas.annotations.media.Schema;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 스키마에 적은 <b>필수 표기·허용값·예시가 실제 서버 거동과 같은지</b> — HTTP 로 확인한다.
 *
 * <p>반사만으로는 "스키마가 스키마와 일치" 를 볼 뿐이다. 필수 세 개만 보내면 200 이고 하나라도 빼면
 * 400 인지, 엔진 허용값이 실제로 등록된 엔진과 같은지, 미리보기 예시가 실제 응답과 같은 형태인지는
 * 서버를 띄워야 보인다. 다른 테스트는 컨텍스트 비용을 물지 않게 반사 테스트와 파일을 나눴다.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@WithMockUser
class ScheduleRequiredFieldContractTest {

    private static final String BASE = "/api/v1/scheduler/schedules";

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired List<SchedulingEngine> engines;

    @DisplayName("필수로 표기한 세 필드만 보내면 생성되고 리소스 종류는 비어서 저장된다")
    @Test
    void the_three_required_fields_alone_create_a_schedule() throws Exception {
        String id = "reqapi-" + Long.toHexString(System.nanoTime());
        String body = mockMvc.perform(post(BASE).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"scheduleId\":\"" + id + "\",\"name\":\"" + id + "\",\"cronExpression\":\"0 0 2 * * ?\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        JsonNode d = objectMapper.readTree(body).get("data");
        try {
            assertTrue(d.get("resourceType").isNull(), "리소스 종류를 비웠는데 저장값이 채워졌다 — 스키마의 '저장은 비고' 가 거짓이 된다");
            assertEquals("PRIORITY_THEN_AGE", d.get("placementOrder").asString());
        } finally {
            mockMvc.perform(delete(BASE).param("ids", d.get("id").asString()));
        }
    }

    @DisplayName("필수 세 필드는 하나라도 빠지면 400 이고 사유에 '필수' 가 있다")
    @Test
    void each_required_field_missing_is_rejected_with_400() throws Exception {
        String id = "reqapi-" + Long.toHexString(System.nanoTime());
        String[][] cases = {
                {"scheduleId", "{\"name\":\"" + id + "\",\"cronExpression\":\"0 0 2 * * ?\"}"},
                {"name", "{\"scheduleId\":\"" + id + "\",\"cronExpression\":\"0 0 2 * * ?\"}"},
                {"cronExpression", "{\"scheduleId\":\"" + id + "\",\"name\":\"" + id + "\"}"},
        };
        for (String[] c : cases) {
            mockMvc.perform(post(BASE).contentType(MediaType.APPLICATION_JSON).content(c[1]))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.message").value(containsString("필수")));
        }
        // 생성(POST) 기준 — 편집(PUT)은 부분 갱신이라 같은 필드를 비워도 400 이 아니다.
    }

    @DisplayName("엔진 허용값·예시는 실제로 등록된 엔진 id 집합과 같다")
    @Test
    void engine_allowable_values_match_the_registered_engines() {
        Set<String> registered = engines.stream().map(SchedulingEngine::getId).collect(Collectors.toSet());
        Schema engine = schemaOf(ScheduleRequest.class, "engine");
        assertEquals(registered, Set.of(engine.allowableValues()),
                "엔진 허용값이 등록된 엔진과 갈렸다 — 엔진을 추가·삭제했으면 스키마도 함께 고친다");
        assertTrue(registered.contains(engine.example()), "엔진 예시가 등록된 엔진이 아니다: " + engine.example());
        assertTrue(registered.contains(schemaOf(ScheduleRunResponse.class, "engineId").example()),
                "실행 이력의 엔진 예시가 등록된 엔진이 아니다");
    }

    @DisplayName("미리보기 예시는 실제 응답과 같은 형태다 — 사본을 비교하지 않고 응답에 대조한다")
    @Test
    void preview_examples_match_a_real_response() throws Exception {
        String cronExample = schemaOf(ScheduleRequest.class, "cronExpression").example();
        String body = mockMvc.perform(post(BASE + "/cron/validate").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"cronExpression\":\"" + cronExample + "\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        JsonNode d = objectMapper.readTree(body).get("data");

        assertEquals(schemaOf(CronPreviewResponse.class, "timezone").example(), d.get("timezone").asString(),
                "시간대 예시가 실제 응답과 다르다");

        JsonNode first = d.get("nextRuns").get(0);
        String localExample = schemaOf(CronPreviewResponse.NextRun.class, "localTime").example();
        // 서버의 포맷 상수는 private 이라 여기 파서는 사본이다. 사본 하나로 서버를 검증하면 원본이 바뀌어도
        // 초록으로 남지만, 예시와 실제 응답을 같은 파서로 둘 다 읽으면 둘이 서로 갈린 것은 잡는다 — 그것이 목적이다.
        DateTimeFormatter probe = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
        assertDoesNotThrow(() -> probe.parse(first.get("localTime").asString()), "실제 localTime 이 예시 형태와 다르다");
        assertDoesNotThrow(() -> probe.parse(localExample), "localTime 예시가 실제 응답 형태와 다르다");
        assertEquals(localExample.length(), first.get("localTime").asString().length(), "localTime 예시와 실제 응답의 길이가 다르다");

        Set<String> koreanDays = Set.of("월요일", "화요일", "수요일", "목요일", "금요일", "토요일", "일요일");
        assertTrue(koreanDays.contains(first.get("dayOfWeek").asString()), "실제 요일이 한국어 이름이 아니다");
        assertTrue(koreanDays.contains(schemaOf(CronPreviewResponse.NextRun.class, "dayOfWeek").example()),
                "요일 예시가 한국어 이름이 아니다");
    }

    private static Schema schemaOf(Class<?> type, String name) {
        RecordComponent c = Arrays.stream(type.getRecordComponents()).filter(x -> x.getName().equals(name)).findFirst()
                .orElseThrow(() -> new AssertionError(type.getSimpleName() + " 에 " + name + " 이 없다"));
        Schema onAccessor = c.getAccessor().getAnnotation(Schema.class);
        return onAccessor != null ? onAccessor : c.getAnnotation(Schema.class);
    }
}
