package com.innogrid.aims.monitoring.controller.alarm;

import java.util.List;

import com.innogrid.aims.common.dto.PageResponse;
import com.innogrid.aims.common.exception.BusinessException;
import com.innogrid.aims.monitoring.dto.log.PortalLogResult;
import com.innogrid.aims.monitoring.dto.log.PortalLogType;
import com.innogrid.aims.monitoring.entity.PortalLogEntry;
import com.innogrid.aims.monitoring.log.PortalLogControllerTestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 알람 발생 이력·정책 API 의 조회 {@code @PortalLog(ACCESS)} 적용 확인 — 목록 조회는 필터를 내용에 남기고 ref 는 비우는지,
 * 알람·정책 단위 조회는 ref 를 거는지, 실패가 FAILURE 와 사유로 남는지 MockMvc 로 본다.
 * 서비스는 대역. 로그는 REQUIRES_NEW 로 커밋되므로 테스트 트랜잭션을 쓰지 않고 직접 지운다.
 */
class AlarmPortalLogTest extends PortalLogControllerTestSupport {

    @BeforeEach
    @AfterEach
    void clean() {
        repository.deleteAll();
    }

    @Test
    void 발생_이력_목록_조회는_ACCESS로_필터를_남기고_ref는_비운다() throws Exception {
        when(alarmHistoryService.getHistories(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(),
                anyInt(), anyInt(), anyString())).thenReturn(PageResponse.of(List.of(), 1, 10));

        mockMvc.perform(get("/api/v1/monitoring/alarms/histories")
                        .param("level", "CRITICAL", "WARNING").param("keyword", "node-01").param("from", "2026-11-01"))
                .andExpect(status().isOk());

        List<PortalLogEntry> logs = repository.findAll();
        assertThat(logs).hasSize(1);
        PortalLogEntry log = logs.get(0);
        assertThat(log.getType()).isEqualTo(PortalLogType.ACCESS);
        assertThat(log.getResult()).isEqualTo(PortalLogResult.SUCCESS);
        assertThat(log.getScreen()).isEqualTo("알람");
        assertThat(log.getAction()).isEqualTo("조회");
        assertThat(log.getContent()).isEqualTo("알람 발생 이력 조회 (레벨: CRITICAL,WARNING, 역할: 전체, 영역: 전체, 자원: 전체, "
                + "정책: 전체, 키워드: node-01, 기간: 2026-11-01~, 1쪽)");
        assertThat(log.getUserId()).isEqualTo("admin@innogrid.com");
        assertThat(log.getRefType()).isNull();
    }

    @Test
    void 알람_단위_조회는_ALARM_HISTORY를_ref로_건다() throws Exception {
        when(alarmHistoryService.getDeliveries(15L)).thenReturn(List.of());

        mockMvc.perform(get("/api/v1/monitoring/alarms/histories/15/deliveries")).andExpect(status().isOk());

        List<PortalLogEntry> logs = repository.findAll();
        assertThat(logs).hasSize(1);
        assertThat(logs.get(0).getContent()).isEqualTo("알람 전송 이력 조회: 알람 id 15");
        assertThat(logs.get(0).getRefType()).isEqualTo("ALARM_HISTORY");
        assertThat(logs.get(0).getRefId()).isEqualTo("15");
    }

    @Test
    void 정책_폼_후보_조회도_ACCESS로_남는다() throws Exception {
        when(alarmPolicyOptionService.getRoleCandidates()).thenReturn(List.of());

        mockMvc.perform(get("/api/v1/monitoring/alarms/policies/role-candidates")).andExpect(status().isOk());

        List<PortalLogEntry> logs = repository.findAll();
        assertThat(logs).hasSize(1);
        assertThat(logs.get(0).getType()).isEqualTo(PortalLogType.ACCESS);
        assertThat(logs.get(0).getScreen()).isEqualTo("알람 정책");
        assertThat(logs.get(0).getContent()).isEqualTo("알람 정책 역할 후보 조회");
        assertThat(logs.get(0).getRefType()).isNull();
    }

    @Test
    void 서비스_예외는_FAILURE와_메시지로_남는다() throws Exception {
        when(alarmHistoryService.getAcknowledgement(99L))
                .thenThrow(new BusinessException(HttpStatus.NOT_FOUND, "확인되지 않은 알람입니다: 99"));

        mockMvc.perform(get("/api/v1/monitoring/alarms/histories/99/acknowledgement")).andExpect(status().isNotFound());

        List<PortalLogEntry> logs = repository.findAll();
        assertThat(logs).hasSize(1);
        assertThat(logs.get(0).getResult()).isEqualTo(PortalLogResult.FAILURE);
        assertThat(logs.get(0).getDescription()).isEqualTo("확인되지 않은 알람입니다: 99");
        assertThat(logs.get(0).getContent()).isEqualTo("알람 확인 내역 조회: 알람 id 99");
    }
}
