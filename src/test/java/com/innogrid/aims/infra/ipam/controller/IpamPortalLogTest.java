package com.innogrid.aims.infra.ipam.controller;

import java.time.OffsetDateTime;
import java.util.List;

import com.innogrid.aims.common.exception.BusinessException;
import com.innogrid.aims.infra.ipam.dto.IpamDtos.ZoneSummary;
import com.innogrid.aims.monitoring.dto.log.PortalLogResult;
import com.innogrid.aims.monitoring.dto.log.PortalLogType;
import com.innogrid.aims.monitoring.entity.PortalLogEntry;
import com.innogrid.aims.monitoring.log.PortalLogControllerTestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * IPAM 3개 컨트롤러(네트워크 구역·서브넷·IP 주소)의 {@code @PortalLog} 적용 확인 — 변경은 AUDIT, 조회는 ACCESS 로
 * 남는지, 생성은 결과 id 를 ref 로 거는지, 범위 예약 내용 문구, 실패가 FAILURE 와 사유로 남는지 MockMvc 로 본다.
 * 서비스는 대역(OpenStack 동기화·DB 접근 차단). 로그는 REQUIRES_NEW 로 커밋되므로 테스트 트랜잭션을 쓰지 않고 직접 지운다.
 */
class IpamPortalLogTest extends PortalLogControllerTestSupport {

    @BeforeEach
    @AfterEach
    void clean() {
        repository.deleteAll();
    }

    @Test
    void 구역_생성은_AUDIT_SUCCESS로_요청_이름과_결과_id를_남긴다() throws Exception {
        OffsetDateTime now = OffsetDateTime.now();
        when(zoneService.createZone(eq("zone-a"), eq("설명"), anyString(), any()))
                .thenReturn(new ZoneSummary(77L, "zone-a", "설명", "admin@innogrid.com", null, now, now, 0, 0));

        mockMvc.perform(post("/api/v1/network/zones").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"zone-a\",\"description\":\"설명\"}"))
                .andExpect(status().isOk());

        List<PortalLogEntry> logs = repository.findAll();
        assertThat(logs).hasSize(1);
        PortalLogEntry log = logs.get(0);
        assertThat(log.getType()).isEqualTo(PortalLogType.AUDIT);
        assertThat(log.getResult()).isEqualTo(PortalLogResult.SUCCESS);
        assertThat(log.getScreen()).isEqualTo("네트워크 구역");
        assertThat(log.getAction()).isEqualTo("생성");
        assertThat(log.getContent()).isEqualTo("네트워크 구역 생성: zone-a");
        assertThat(log.getUserId()).isEqualTo("admin@innogrid.com");
        assertThat(log.getRefType()).isEqualTo("NETWORK_ZONE");
        assertThat(log.getRefId()).isEqualTo("77");
        assertThat(log.getHttpMethod()).isEqualTo("POST");
        assertThat(log.getRequestUri()).isEqualTo("/api/v1/network/zones");
    }

    @Test
    void 서브넷_목록_조회는_ACCESS로_필터를_남기고_필터_없음은_전체로_표기한다() throws Exception {
        when(subnetService.listSubnets(isNull())).thenReturn(List.of());
        when(subnetService.listSubnets(3L)).thenReturn(List.of());

        mockMvc.perform(get("/api/v1/network/subnets")).andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/network/subnets").param("zoneId", "3")).andExpect(status().isOk());

        List<PortalLogEntry> logs = repository.findAll();
        assertThat(logs).hasSize(2);
        assertThat(logs).allSatisfy(log -> {
            assertThat(log.getType()).isEqualTo(PortalLogType.ACCESS);
            assertThat(log.getResult()).isEqualTo(PortalLogResult.SUCCESS);
            assertThat(log.getScreen()).isEqualTo("서브넷");
            assertThat(log.getAction()).isEqualTo("조회");
            assertThat(log.getRefType()).isNull();
        });
        assertThat(logs).extracting(PortalLogEntry::getContent)
                .containsExactlyInAnyOrder("서브넷 목록 조회 (구역: 전체)", "서브넷 목록 조회 (구역: 3)");
    }

    @Test
    void IP_범위_예약은_시작과_끝을_함께_남기고_단일_예약은_시작만_남긴다() throws Exception {
        when(ipAddressService.reserve(anyLong(), anyString(), any(), any(), anyString(), any())).thenReturn(1);

        mockMvc.perform(post("/api/v1/network/ip-addresses").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"subnetId\":5,\"ipStart\":\"10.0.0.10\",\"ipEnd\":\"10.0.0.20\"}"))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/v1/network/ip-addresses").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"subnetId\":5,\"ipStart\":\"10.0.0.30\"}"))
                .andExpect(status().isOk());

        List<PortalLogEntry> logs = repository.findAll();
        assertThat(logs).hasSize(2);
        assertThat(logs).extracting(PortalLogEntry::getContent).containsExactlyInAnyOrder(
                "IP 주소 예약: 10.0.0.10 ~ 10.0.0.20 (서브넷 id 5)",
                "IP 주소 예약: 10.0.0.30 (서브넷 id 5)");
        assertThat(logs).allSatisfy(log -> {
            assertThat(log.getScreen()).isEqualTo("IP주소");
            assertThat(log.getAction()).isEqualTo("예약");
            assertThat(log.getRefType()).isEqualTo("SUBNET");
            assertThat(log.getRefId()).isEqualTo("5");
        });
    }

    @Test
    void 서비스_예외는_FAILURE와_메시지로_남고_ref는_비운다() throws Exception {
        doThrow(new BusinessException(HttpStatus.NOT_FOUND, "IP를 찾을 수 없습니다: 99"))
                .when(ipAddressService).delete(eq(99L), anyString(), any());

        mockMvc.perform(delete("/api/v1/network/ip-addresses/99")).andExpect(status().isNotFound());

        List<PortalLogEntry> logs = repository.findAll();
        assertThat(logs).hasSize(1);
        PortalLogEntry log = logs.get(0);
        assertThat(log.getType()).isEqualTo(PortalLogType.AUDIT);
        assertThat(log.getResult()).isEqualTo(PortalLogResult.FAILURE);
        assertThat(log.getAction()).isEqualTo("삭제");
        assertThat(log.getContent()).isEqualTo("IP 주소 삭제: id 99");
        assertThat(log.getDescription()).isEqualTo("IP를 찾을 수 없습니다: 99");
        assertThat(log.getRefType()).isNull();                                   // ref 는 성공 시에만 평가한다(§4)
    }

    @Test
    void 필수_파라미터_누락_400은_폴백_인터셉터가_고정_문구로_남긴다() throws Exception {
        mockMvc.perform(get("/api/v1/network/ip-addresses/histories"))           // subnetId 없음 → 메서드 진입 전 400
                .andExpect(status().isBadRequest());

        List<PortalLogEntry> logs = repository.findAll();
        assertThat(logs).hasSize(1);
        assertThat(logs.get(0).getType()).isEqualTo(PortalLogType.ACCESS);
        assertThat(logs.get(0).getResult()).isEqualTo(PortalLogResult.FAILURE);
        assertThat(logs.get(0).getContent()).isEqualTo("IP주소 조회 요청 검증 실패");
    }
}
