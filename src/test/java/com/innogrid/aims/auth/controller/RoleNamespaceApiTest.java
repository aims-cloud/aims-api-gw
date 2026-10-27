package com.innogrid.aims.auth.controller;

import java.util.List;
import java.util.Map;

import com.innogrid.aims.auth.entity.Role;
import com.innogrid.aims.auth.repository.RoleRepository;
import com.innogrid.aims.cloudbarista.tumblebug.TumblebugClient;
import com.innogrid.aims.infra.repository.RoleNamespaceRepository;
import com.innogrid.aims.monitoring.dto.log.PortalLogResult;
import com.innogrid.aims.monitoring.dto.log.PortalLogType;
import com.innogrid.aims.monitoring.entity.PortalLogEntry;
import com.innogrid.aims.monitoring.repository.PortalLogRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.client.ResourceAccessException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 역할 ↔ Namespace 배정 API — 실제 H2 저장소로 배정·조회·후보·해제, 포털 로그(AUDIT·ROLE ref),
 * 배정이 남은 역할의 삭제(외래키), Tumblebug 연결 실패 503 을 본다. Tumblebug 만 가짜로 둔다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@WithMockUser(username = "admin@innogrid.com")
class RoleNamespaceApiTest {

    private static final String ROLE_NAME = "ROLE_NAMESPACE_API_TEST";

    @Autowired MockMvc mockMvc;
    @Autowired RoleRepository roleRepository;
    @Autowired RoleNamespaceRepository roleNamespaceRepository;
    @Autowired PortalLogRepository logRepository;
    @MockitoBean TumblebugClient tumblebug;

    Role role;

    @BeforeEach
    void setUp() {
        clean();
        role = roleRepository.save(new Role(ROLE_NAME, "Namespace 배정 시험"));
        when(tumblebug.listNamespaces()).thenReturn(Map.of("ns", List.of(
                Map.of("id", "aws", "name", "AWS 테스트"),
                Map.of("id", "gcp", "name", "GCP 테스트"))));
    }

    @AfterEach
    void clean() {
        logRepository.deleteAll();
        roleRepository.findAll().stream().filter(r -> ROLE_NAME.equals(r.getName())).forEach(r -> {
            roleNamespaceRepository.deleteAll(roleNamespaceRepository.findByRole_Id(r.getId()));
            roleRepository.delete(r);
        });
    }

    @Test
    void 배정하고_조회하고_해제하면_포털_로그가_ROLE_ref로_남는다() throws Exception {
        mockMvc.perform(post("/api/v1/roles/{roleId}/namespaces", role.getId()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nsIds\":[\"gcp\",\"aws\"]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].nsId").value("aws"))
                .andExpect(jsonPath("$.data[0].name").value("AWS 테스트"))
                .andExpect(jsonPath("$.data[1].nsId").value("gcp"));

        mockMvc.perform(get("/api/v1/roles/{roleId}/namespaces/candidates", role.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].assigned").value(true))
                .andExpect(jsonPath("$.data[1].assigned").value(true));

        mockMvc.perform(delete("/api/v1/roles/{roleId}/namespaces/{nsId}", role.getId(), "gcp"))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/roles/{roleId}/namespaces", role.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].nsId").value("aws"));

        List<PortalLogEntry> logs = logRepository.findAll();
        assertThat(logs).extracting(PortalLogEntry::getAction).containsExactlyInAnyOrder("Namespace 배정", "Namespace 배정 해제");
        assertThat(logs).allSatisfy(l -> {
            assertThat(l.getType()).isEqualTo(PortalLogType.AUDIT);
            assertThat(l.getResult()).isEqualTo(PortalLogResult.SUCCESS);
            assertThat(l.getRefType()).isEqualTo("ROLE");
            assertThat(l.getRefId()).isEqualTo(String.valueOf(role.getId()));
        });
        assertThat(logs).extracting(PortalLogEntry::getContent).contains(
                "Namespace 배정: 역할 id " + role.getId() + ", gcp,aws",                 // SpEL 이 목록을 쉼표로 잇는다
                "Namespace 배정 해제: 역할 id " + role.getId() + ", gcp");
    }

    @Test
    void Tumblebug에_없는_nsId는_400이고_배정되지_않는다() throws Exception {
        mockMvc.perform(post("/api/v1/roles/{roleId}/namespaces", role.getId()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nsIds\":[\"aws\",\"typo\"]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Tumblebug 에 없는 Namespace 입니다: typo"));
        assertThat(roleNamespaceRepository.findByRole_Id(role.getId())).isEmpty();
    }

    @Test
    void 배정이_남은_역할도_삭제된다() throws Exception {
        mockMvc.perform(post("/api/v1/roles/{roleId}/namespaces", role.getId()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nsIds\":[\"aws\"]}"))
                .andExpect(status().isOk());

        mockMvc.perform(delete("/api/v1/roles/{roleId}", role.getId()))
                .andExpect(status().isOk());                                    // 예전에는 role_namespaces 외래키로 500

        assertThat(roleRepository.findById(role.getId())).isEmpty();
        assertThat(roleNamespaceRepository.findByRole_Id(role.getId())).isEmpty();
    }

    @Test
    void 배정되지_않은_nsId_해제와_없는_역할은_404() throws Exception {
        mockMvc.perform(delete("/api/v1/roles/{roleId}/namespaces/{nsId}", role.getId(), "aws"))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/v1/roles/{roleId}/namespaces", 999_999L))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("역할을 찾을 수 없습니다"));
    }

    @Test
    void Tumblebug_연결_실패면_후보_조회는_503() throws Exception {
        when(tumblebug.listNamespaces()).thenThrow(new ResourceAccessException("Connection refused"));

        mockMvc.perform(get("/api/v1/roles/{roleId}/namespaces/candidates", role.getId()))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.success").value(false));
    }
}
