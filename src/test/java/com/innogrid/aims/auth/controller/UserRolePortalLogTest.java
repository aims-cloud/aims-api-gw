package com.innogrid.aims.auth.controller;

import java.util.List;
import java.util.Optional;

import com.innogrid.aims.auth.entity.Role;
import com.innogrid.aims.auth.entity.User;
import com.innogrid.aims.auth.repository.RoleRepository;
import com.innogrid.aims.auth.repository.UserRepository;
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
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 사용자·역할 관리 API 포털 로그(AUDIT) — 인증 사용자가 기록되는지, 생성은 결과 id 를 ref 로 거는지,
 * 실패(없는 역할 부여)가 FAILURE 와 사유로 남는지, 비밀번호가 내용에 들어가지 않는지 MockMvc 로 본다.
 * 저장소는 실제 H2. 로그는 REQUIRES_NEW 로 커밋되므로 테스트 트랜잭션을 쓰지 않고 만든 행을 직접 지운다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@WithMockUser(username = "admin@innogrid.com")
class UserRolePortalLogTest {

    private static final String USER_EMAIL = "portal-log-test@innogrid.com";
    private static final String ROLE_NAME = "PORTAL_LOG_TEST_ROLE";

    @Autowired
    MockMvc mockMvc;
    @Autowired
    PortalLogRepository logRepository;
    @Autowired
    UserRepository userRepository;
    @Autowired
    RoleRepository roleRepository;

    @BeforeEach
    @AfterEach
    void clean() {
        logRepository.deleteAll();
        userRepository.findByEmail(USER_EMAIL).ifPresent(userRepository::delete);
        roleRepository.findAll().stream().filter(r -> ROLE_NAME.equals(r.getName())).forEach(roleRepository::delete);
    }

    @Test
    void 사용자_생성은_AUDIT_SUCCESS로_인증_사용자와_결과_id를_남기고_비밀번호는_남기지_않는다() throws Exception {
        mockMvc.perform(post("/api/v1/users").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + USER_EMAIL + "\",\"password\":\"temp1234!\",\"name\":\"테스트\",\"organization\":\"이노그리드\"}"))
                .andExpect(status().isOk());

        Optional<User> created = userRepository.findByEmail(USER_EMAIL);
        assertThat(created).isPresent();

        List<PortalLogEntry> logs = logRepository.findAll();
        assertThat(logs).hasSize(1);
        PortalLogEntry log = logs.get(0);
        assertThat(log.getType()).isEqualTo(PortalLogType.AUDIT);
        assertThat(log.getResult()).isEqualTo(PortalLogResult.SUCCESS);
        assertThat(log.getScreen()).isEqualTo("사용자 관리");
        assertThat(log.getAction()).isEqualTo("생성");
        assertThat(log.getContent()).isEqualTo("사용자 생성: " + USER_EMAIL);
        assertThat(log.getContent()).doesNotContain("temp1234");
        assertThat(log.getUserId()).isEqualTo("admin@innogrid.com");
        assertThat(log.getRefType()).isEqualTo("USER");
        assertThat(log.getRefId()).isEqualTo(String.valueOf(created.get().getId()));
        assertThat(log.getHttpMethod()).isEqualTo("POST");
        assertThat(log.getRequestUri()).isEqualTo("/api/v1/users");
    }

    @Test
    void 없는_역할_부여는_FAILURE와_사유로_남고_내용은_요청_인자로_만든다() throws Exception {
        User user = userRepository.save(new User(USER_EMAIL, "encoded", "테스트", "이노그리드"));

        mockMvc.perform(post("/api/v1/users/{userId}/roles/{roleId}", user.getId(), 999_999L))
                .andExpect(status().isNotFound());

        List<PortalLogEntry> logs = logRepository.findAll();
        assertThat(logs).hasSize(1);
        assertThat(logs.get(0).getAction()).isEqualTo("역할 부여");
        assertThat(logs.get(0).getResult()).isEqualTo(PortalLogResult.FAILURE);
        assertThat(logs.get(0).getDescription()).isEqualTo("역할을 찾을 수 없습니다");
        assertThat(logs.get(0).getContent()).isEqualTo("역할 부여: 사용자 id " + user.getId() + ", 역할 id 999999");
        assertThat(logs.get(0).getRefId()).isNull();                       // 실패 시 ref 는 걸지 않는다
    }

    @Test
    void 사용자_상태_변경은_대상_id와_새_상태를_남긴다() throws Exception {
        User user = userRepository.save(new User(USER_EMAIL, "encoded", "테스트", "이노그리드"));

        mockMvc.perform(patch("/api/v1/users/{userId}/status", user.getId()).param("status", "inactive"))
                .andExpect(status().isOk());

        List<PortalLogEntry> logs = logRepository.findAll();
        assertThat(logs).hasSize(1);
        assertThat(logs.get(0).getAction()).isEqualTo("상태 변경");
        assertThat(logs.get(0).getContent()).isEqualTo("사용자 상태 변경: id " + user.getId() + " → inactive");
        assertThat(logs.get(0).getRefId()).isEqualTo(String.valueOf(user.getId()));
    }

    @Test
    void 역할_생성과_권한_추가와_삭제는_ROLE_ref로_남는다() throws Exception {
        mockMvc.perform(post("/api/v1/roles").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"" + ROLE_NAME + "\",\"description\":\"포털 로그 시험\"}"))
                .andExpect(status().isOk());
        Role role = roleRepository.findAll().stream().filter(r -> ROLE_NAME.equals(r.getName())).findFirst().orElseThrow();

        mockMvc.perform(post("/api/v1/roles/{roleId}/permissions", role.getId()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"resource\":\"vm\",\"action\":\"create\",\"description\":\"VM 생성\"}"))
                .andExpect(status().isOk());
        mockMvc.perform(delete("/api/v1/roles/{roleId}", role.getId()))
                .andExpect(status().isOk());

        List<PortalLogEntry> logs = logRepository.findAll();
        assertThat(logs).hasSize(3);
        assertThat(logs).allSatisfy(log -> {
            assertThat(log.getType()).isEqualTo(PortalLogType.AUDIT);
            assertThat(log.getScreen()).isEqualTo("역할/권한");
            assertThat(log.getRefType()).isEqualTo("ROLE");
            assertThat(log.getRefId()).isEqualTo(String.valueOf(role.getId()));
            assertThat(log.getUserId()).isEqualTo("admin@innogrid.com");
        });
        assertThat(logs).extracting(PortalLogEntry::getContent).containsExactlyInAnyOrder(
                "역할 생성: " + ROLE_NAME,
                "권한 추가: 역할 id " + role.getId() + ", vm:create",
                "역할 삭제: id " + role.getId());
    }
}
