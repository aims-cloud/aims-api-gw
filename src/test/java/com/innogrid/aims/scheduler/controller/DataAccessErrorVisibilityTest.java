package com.innogrid.aims.scheduler.controller;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.dao.InvalidDataAccessResourceUsageException;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.innogrid.aims.scheduler.service.NodeResourceCollector;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 데이터베이스 오류가 <b>403 으로 위장되지 않는지</b> 본다.
 *
 * <p>잡는 처리기가 없으면 예외는 오류 처리 경로로 넘어가고, 그 경로가 인증을 요구해 호출자에게는
 * 403 이 된다. 그러면 파라미터를 빠뜨린 것인지 권한이 없는 것인지 데이터베이스가 거절한 것인지
 * 구분할 수 없다.</p>
 *
 * <p>이 구분이 중요한 이유는 시험 환경이 H2 이고 운영이 PostgreSQL 이기 때문이다. 방언 차이로 나는
 * 문제는 단위 시험을 통과하고 배포에서만 드러나므로, <b>배포 검증이 보는 신호</b>가 원인을 가리켜야
 * 한다. 실제로 널 파라미터 타입 추론 실패가 403 으로 보여 원인 파악이 늦어진 적이 있다.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@WithMockUser
class DataAccessErrorVisibilityTest {

    @Autowired MockMvc mockMvc;

    @MockitoBean NodeResourceCollector collector;

    @DisplayName("데이터베이스 오류는 403 이 아니라 500 과 고정 사유로 나온다")
    @Test
    void a_database_failure_surfaces_as_500_with_a_reason() throws Exception {
        when(collector.collect()).thenThrow(new InvalidDataAccessResourceUsageException(
                "could not determine data type of parameter $5"));

        mockMvc.perform(get("/api/v1/scheduler/nodes"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value(containsString("데이터베이스 처리에 실패했습니다")))
                .andExpect(jsonPath("$.message").value(not(containsString("could not determine data type"))));
    }

    @DisplayName("사유에는 드라이버 메시지의 첫 줄도 싣지 않아 제약명과 질의 원문이 새지 않는다")
    @Test
    void the_reason_hides_driver_details() throws Exception {
        when(collector.collect()).thenThrow(new InvalidDataAccessResourceUsageException(
                "ERROR: violates check constraint \"workloads_status_check\"\n  Query: select secret_column from workloads"));

        mockMvc.perform(get("/api/v1/scheduler/nodes"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.message").value(not(containsString("workloads_status_check"))))
                .andExpect(jsonPath("$.message").value(not(containsString("secret_column"))))
                .andExpect(jsonPath("$.message").value("데이터베이스 처리에 실패했습니다. 서버 로그를 확인하세요"));
    }

    @DisplayName("업무 예외는 그대로 자기 상태 코드로 나온다 — 이 처리기가 가로채지 않는다")
    @Test
    void business_errors_are_not_swallowed() throws Exception {
        mockMvc.perform(get("/api/v1/scheduler/schedules/999999"))
                .andExpect(status().isNotFound());
    }
}
