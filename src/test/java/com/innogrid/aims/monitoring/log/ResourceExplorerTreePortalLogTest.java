package com.innogrid.aims.monitoring.log;

import java.util.List;

import com.innogrid.aims.common.exception.BusinessException;
import com.innogrid.aims.monitoring.dto.log.PortalLogResult;
import com.innogrid.aims.monitoring.dto.log.PortalLogType;
import com.innogrid.aims.monitoring.dto.resourceexplorer.tree.ResourceTreeNodeResponse;
import com.innogrid.aims.monitoring.dto.resourceexplorer.tree.ResourceTreeNodeType;
import com.innogrid.aims.monitoring.entity.PortalLogEntry;
import com.innogrid.aims.monitoring.repository.PortalLogRepository;
import com.innogrid.aims.monitoring.service.resourceexplorer.ResourceExplorerTreeService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpStatus;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * @PortalLog 실제 컨트롤러 적용 확인 — 자원 탐색기 트리 API(ACCESS) 를 MockMvc 로 호출해
 * 필터(요청 ID) → 보안(인증 사용자) → Aspect(성공/실패) → 폴백 인터셉터(진입 전 400) 가 한 줄로 이어져 portal_logs 에 남는지 본다.
 * 트리 서비스는 대역(Tumblebug·Thanos 호출 차단). 로그는 REQUIRES_NEW 로 커밋되므로 테스트 트랜잭션을 쓰지 않고 직접 지운다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@WithMockUser(username = "admin@innogrid.com")
class ResourceExplorerTreePortalLogTest {

    @Autowired
    MockMvc mockMvc;
    @Autowired
    PortalLogRepository repository;
    @MockitoBean
    ResourceExplorerTreeService treeService;

    @BeforeEach
    @AfterEach
    void clean() {
        repository.deleteAll();
    }

    @Test
    void 트리_조회는_ACCESS_로그로_남고_요청_ID가_응답_헤더와_같다() throws Exception {
        when(treeService.getTree(any())).thenReturn(ResourceTreeNodeResponse.of(
                "root", null, "전체 자원", ResourceTreeNodeType.ROOT, null, null, null, null, null, List.of()));

        MvcResult result = mockMvc.perform(get("/api/v1/monitoring/resource-explorer/tree")
                        .param("keyword", "web").header("X-Forwarded-For", "203.0.113.7, 10.0.0.1"))
                .andExpect(status().isOk())
                .andReturn();

        List<PortalLogEntry> logs = repository.findAll();
        assertThat(logs).hasSize(1);
        PortalLogEntry log = logs.get(0);
        assertThat(log.getType()).isEqualTo(PortalLogType.ACCESS);
        assertThat(log.getResult()).isEqualTo(PortalLogResult.SUCCESS);
        assertThat(log.getScreen()).isEqualTo("자원 탐색기");
        assertThat(log.getAction()).isEqualTo("조회");
        assertThat(log.getContent()).isEqualTo("전체 자원 트리 조회 (MDC: 전체, 키워드: web)");
        assertThat(log.getUserId()).isEqualTo("admin@innogrid.com");
        assertThat(log.getUserName()).isEqualTo("admin@innogrid.com");            // users 테이블에 없으면 ID 로 대체
        assertThat(log.getClientIp()).isEqualTo("203.0.113.7");
        assertThat(log.getHttpMethod()).isEqualTo("GET");
        assertThat(log.getRequestUri()).isEqualTo("/api/v1/monitoring/resource-explorer/tree");
        assertThat(log.getRequestId()).isNotBlank()
                .isEqualTo(result.getResponse().getHeader(RequestIdFilter.HEADER));
        assertThat(log.getCompletedAt()).isNotNull();
    }

    @Test
    void 서비스_예외는_FAILURE와_메시지로_남고_응답은_기존_예외_처리기가_만든다() throws Exception {
        when(treeService.getChildren(anyString()))
                .thenThrow(new BusinessException(HttpStatus.NOT_FOUND, "트리 노드를 찾을 수 없습니다: x"));

        mockMvc.perform(get("/api/v1/monitoring/resource-explorer/tree/x/children"))
                .andExpect(status().isNotFound());

        List<PortalLogEntry> logs = repository.findAll();
        assertThat(logs).hasSize(1);
        assertThat(logs.get(0).getResult()).isEqualTo(PortalLogResult.FAILURE);
        assertThat(logs.get(0).getDescription()).isEqualTo("트리 노드를 찾을 수 없습니다: x");
        assertThat(logs.get(0).getContent()).isEqualTo("자원 트리 자식 조회: x");
    }

    @Test
    void 필수_파라미터_누락_400은_폴백_인터셉터가_고정_문구로_남긴다() throws Exception {
        mockMvc.perform(get("/api/v1/monitoring/resource-explorer/tree/search"))     // keyword 없음 → 메서드 진입 전 400
                .andExpect(status().isBadRequest());

        List<PortalLogEntry> logs = repository.findAll();
        assertThat(logs).hasSize(1);
        assertThat(logs.get(0).getType()).isEqualTo(PortalLogType.ACCESS);
        assertThat(logs.get(0).getResult()).isEqualTo(PortalLogResult.FAILURE);
        assertThat(logs.get(0).getContent()).isEqualTo("자원 탐색기 검색 요청 검증 실패");
        assertThat(logs.get(0).getDescription()).isNotBlank();
    }
}
