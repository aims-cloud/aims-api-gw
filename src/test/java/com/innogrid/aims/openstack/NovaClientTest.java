package com.innogrid.aims.openstack;

import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import com.innogrid.aims.common.exception.BusinessException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;

class NovaClientTest {

    private static final String COMPUTE_URL = "http://nova:8774/v2.1";

    private MockRestServiceServer server;
    private NovaClient client;

    @BeforeEach
    void setUp() {
        OpenStackProperties props = new OpenStackProperties();
        props.setComputeUrl(COMPUTE_URL);

        KeystoneClient keystone = mock(KeystoneClient.class);
        when(keystone.getToken()).thenReturn("tok-abc");

        RestClient.Builder builder = RestClient.builder().baseUrl(COMPUTE_URL);
        server = MockRestServiceServer.bindTo(builder).build();
        client = new NovaClient(props, keystone, builder.build());
    }

    @Test
    void list_servers_sends_auth_token() {
        server.expect(requestTo(COMPUTE_URL + "/servers/detail"))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header("X-Auth-Token", "tok-abc"))
                .andRespond(withStatus(HttpStatus.OK)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"servers\":[{\"id\":\"vm-1\",\"name\":\"web-01\",\"status\":\"ACTIVE\"}]}"));

        Map<String, Object> result = client.listServers();

        assertThat(result).containsKey("servers");
        server.verify();
    }

    @Test
    void get_server_returns_detail() {
        server.expect(requestTo(COMPUTE_URL + "/servers/vm-1"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withStatus(HttpStatus.OK)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"server\":{\"id\":\"vm-1\",\"name\":\"web-01\"}}"));

        Map<String, Object> result = client.getServer("vm-1");

        assertThat(result).containsKey("server");
        server.verify();
    }

    @Test
    void unconfigured_compute_url_throws_service_unavailable() {
        NovaClient unconfigured = new NovaClient(new OpenStackProperties(), mock(KeystoneClient.class));

        assertThatThrownBy(unconfigured::listServers)
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getStatus())
                        .isEqualTo(HttpStatus.SERVICE_UNAVAILABLE));
    }
}
