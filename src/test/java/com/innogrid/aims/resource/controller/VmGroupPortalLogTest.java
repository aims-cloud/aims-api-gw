package com.innogrid.aims.resource.controller;

import java.time.OffsetDateTime;
import java.util.List;

import com.innogrid.aims.common.exception.BusinessException;
import com.innogrid.aims.monitoring.dto.log.PortalLogResult;
import com.innogrid.aims.monitoring.dto.log.PortalLogType;
import com.innogrid.aims.monitoring.entity.PortalLogEntry;
import com.innogrid.aims.monitoring.repository.PortalLogRepository;
import com.innogrid.aims.resource.dto.VmGroupDtos.GroupSummary;
import com.innogrid.aims.resource.dto.VmGroupDtos.NetworkMap;
import com.innogrid.aims.resource.service.VmGroupService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 가상서버 그룹 API 의 {@code @PortalLog} 적용 확인 — 변경은 AUDIT, 조회는 ACCESS(ref = VM_GROUP) 로 남는지,
 * 생성은 결과 id 를 ref 로 거는지, 서버 추가·네트워크 맵 저장은 건수를 내용에 넣는지, 실패가 FAILURE 와 사유로 남는지 MockMvc 로 본다.
 * 서비스는 대역(OpenStack 조회 차단). 로그는 REQUIRES_NEW 로 커밋되므로 테스트 트랜잭션을 쓰지 않고 직접 지운다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@WithMockUser(username = "admin@innogrid.com")
class VmGroupPortalLogTest {

    @Autowired
    MockMvc mockMvc;
    @Autowired
    PortalLogRepository repository;
    @MockitoBean
    VmGroupService service;

    @BeforeEach
    @AfterEach
    void clean() {
        repository.deleteAll();
    }

    @Test
    void 그룹_생성은_AUDIT_SUCCESS로_이름과_배포영역을_남기고_결과_id를_ref로_건다() throws Exception {
        when(service.createGroup(eq("kr-1"), eq("web-tier"), any(), anyBoolean(), anyString(), any()))
                .thenReturn(new GroupSummary(31L, "kr-1", "web-tier", null, true, "admin@innogrid.com", OffsetDateTime.now(), 0, 0));

        mockMvc.perform(post("/api/v1/vm-groups").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"deployZone\":\"kr-1\",\"name\":\"web-tier\"}"))
                .andExpect(status().isOk());

        List<PortalLogEntry> logs = repository.findAll();
        assertThat(logs).hasSize(1);
        PortalLogEntry log = logs.get(0);
        assertThat(log.getType()).isEqualTo(PortalLogType.AUDIT);
        assertThat(log.getResult()).isEqualTo(PortalLogResult.SUCCESS);
        assertThat(log.getScreen()).isEqualTo("가상서버 그룹");
        assertThat(log.getAction()).isEqualTo("생성");
        assertThat(log.getContent()).isEqualTo("가상서버 그룹 생성: web-tier (배포영역 kr-1)");
        assertThat(log.getUserId()).isEqualTo("admin@innogrid.com");
        assertThat(log.getRefType()).isEqualTo("VM_GROUP");
        assertThat(log.getRefId()).isEqualTo("31");
    }

    @Test
    void 서버_추가와_네트워크_맵_저장은_건수를_내용에_남긴다() throws Exception {
        when(service.saveNetworkMap(eq(31L), any(), anyString(), any())).thenReturn(new NetworkMap(31L, List.of(), List.of()));

        mockMvc.perform(post("/api/v1/vm-groups/31/servers").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"serverIds\":[\"srv-a\",\"srv-b\"]}"))
                .andExpect(status().isOk());
        mockMvc.perform(put("/api/v1/vm-groups/31/network-map").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"edges\":[{\"sourceServerId\":\"srv-a\",\"sourceEndpoint\":\"eth0\",\"targetServerId\":\"srv-b\",\"targetEndpoint\":\"eth0\"}]}"))
                .andExpect(status().isOk());

        List<PortalLogEntry> logs = repository.findAll();
        assertThat(logs).hasSize(2);
        assertThat(logs).extracting(PortalLogEntry::getAction).containsExactlyInAnyOrder("서버 추가", "네트워크 맵 저장");
        assertThat(logs).extracting(PortalLogEntry::getContent).containsExactlyInAnyOrder(
                "가상서버 그룹 서버 추가: id 31 (2대: srv-a,srv-b)",          // SpEL 은 리스트를 쉼표로만 잇는다
                "가상서버 그룹 네트워크 맵 저장: id 31 (연결 1개)");
        assertThat(logs).allSatisfy(log -> {
            assertThat(log.getType()).isEqualTo(PortalLogType.AUDIT);
            assertThat(log.getRefType()).isEqualTo("VM_GROUP");
            assertThat(log.getRefId()).isEqualTo("31");
        });
    }

    @Test
    void 그룹_상세_조회는_ACCESS로_남고_그룹을_ref로_건다() throws Exception {
        when(service.getGroupSummary(31L))
                .thenReturn(new GroupSummary(31L, "kr-1", "web-tier", null, true, null, OffsetDateTime.now(), 2, 1));

        mockMvc.perform(get("/api/v1/vm-groups/31")).andExpect(status().isOk());

        List<PortalLogEntry> logs = repository.findAll();
        assertThat(logs).hasSize(1);
        assertThat(logs.get(0).getType()).isEqualTo(PortalLogType.ACCESS);
        assertThat(logs.get(0).getAction()).isEqualTo("조회");
        assertThat(logs.get(0).getContent()).isEqualTo("가상서버 그룹 조회: id 31");
        assertThat(logs.get(0).getRefType()).isEqualTo("VM_GROUP");
        assertThat(logs.get(0).getRefId()).isEqualTo("31");
    }

    @Test
    void 서비스_예외는_FAILURE와_메시지로_남는다() throws Exception {
        doThrow(new BusinessException(HttpStatus.BAD_REQUEST, "추가할 가상서버를 선택해주세요"))
                .when(service).addServers(eq(31L), any(), anyString(), any());

        mockMvc.perform(post("/api/v1/vm-groups/31/servers").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"serverIds\":[]}"))
                .andExpect(status().isBadRequest());

        List<PortalLogEntry> logs = repository.findAll();
        assertThat(logs).hasSize(1);
        assertThat(logs.get(0).getResult()).isEqualTo(PortalLogResult.FAILURE);
        assertThat(logs.get(0).getDescription()).isEqualTo("추가할 가상서버를 선택해주세요");
        assertThat(logs.get(0).getContent()).isEqualTo("가상서버 그룹 서버 추가: id 31 (0대: )");
    }
}
