package com.innogrid.aims.openstack;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import com.innogrid.aims.common.exception.BusinessException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;

class KeystoneClientTest {

    private static final String AUTH_URL = "http://keystone:5000/v3";

    private OpenStackProperties props;
    private MockRestServiceServer server;
    private KeystoneClient client;

    @BeforeEach
    void setUp() {
        props = new OpenStackProperties();
        props.setAuthUrl(AUTH_URL);
        props.setUsername("aims");
        props.setPassword("secret");
        props.setProjectName("aims-project");
        props.setDomainName("Default");

        RestClient.Builder builder = RestClient.builder().baseUrl(AUTH_URL);
        server = MockRestServiceServer.bindTo(builder).build();
        client = new KeystoneClient(props, builder.build());
    }

    private void expectTokenIssue(String token, Instant expiresAt) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Subject-Token", token);
        server.expect(requestTo(AUTH_URL + "/auth/tokens"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(jsonPath("$.auth.identity.methods[0]").value("password"))
                .andExpect(jsonPath("$.auth.identity.password.user.name").value("aims"))
                .andExpect(jsonPath("$.auth.scope.project.name").value("aims-project"))
                .andRespond(withStatus(HttpStatus.CREATED)
                        .headers(headers)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"token\":{\"expires_at\":\"" + expiresAt + "\"}}"));
    }

    @Test
    void issues_token_with_password_auth() {
        expectTokenIssue("tok-123", Instant.now().plus(1, ChronoUnit.HOURS));

        String token = client.getToken();

        assertThat(token).isEqualTo("tok-123");
        server.verify();
    }

    @Test
    void reuses_cached_token_until_expiry() {
        expectTokenIssue("tok-123", Instant.now().plus(1, ChronoUnit.HOURS));

        String first = client.getToken();
        String second = client.getToken();

        assertThat(first).isEqualTo(second);
        server.verify(); // 발급 요청이 1회만 발생
    }

    @Test
    void reissues_token_when_near_expiry() {
        // 만료까지 30초 남은 토큰 → REFRESH_MARGIN(60초) 이내이므로 다음 호출에서 재발급
        expectTokenIssue("tok-old", Instant.now().plus(30, ChronoUnit.SECONDS));
        expectTokenIssue("tok-new", Instant.now().plus(1, ChronoUnit.HOURS));

        client.getToken();
        String second = client.getToken();

        assertThat(second).isEqualTo("tok-new");
        server.verify();
    }

    @Test
    void invalidate_forces_reissue() {
        expectTokenIssue("tok-1", Instant.now().plus(1, ChronoUnit.HOURS));
        expectTokenIssue("tok-2", Instant.now().plus(1, ChronoUnit.HOURS));

        client.getToken();
        client.invalidate();
        String token = client.getToken();

        assertThat(token).isEqualTo("tok-2");
        server.verify();
    }

    @Test
    void missing_subject_token_header_throws_bad_gateway() {
        server.expect(requestTo(AUTH_URL + "/auth/tokens"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withStatus(HttpStatus.CREATED)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"token\":{}}"));

        assertThatThrownBy(client::getToken)
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getStatus())
                        .isEqualTo(HttpStatus.BAD_GATEWAY));
    }

    @Test
    void list_projects_sends_issued_token() {
        expectTokenIssue("tok-123", Instant.now().plus(1, ChronoUnit.HOURS));
        server.expect(requestTo(AUTH_URL + "/auth/projects"))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header("X-Auth-Token", "tok-123"))
                .andRespond(withStatus(HttpStatus.OK)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"projects\":[{\"id\":\"p-1\",\"name\":\"admin\"}]}"));

        var result = client.listProjects();

        assertThat(result).containsKey("projects");
        server.verify();
    }

    @Test
    void unconfigured_properties_throw_service_unavailable() {
        OpenStackProperties empty = new OpenStackProperties();
        KeystoneClient unconfigured = new KeystoneClient(empty);

        assertThatThrownBy(unconfigured::getToken)
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getStatus())
                        .isEqualTo(HttpStatus.SERVICE_UNAVAILABLE));
    }
}
