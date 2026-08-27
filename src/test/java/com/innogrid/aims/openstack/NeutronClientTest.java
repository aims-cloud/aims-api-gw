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

class NeutronClientTest {

    private static final String NETWORK_URL = "http://neutron:9696";

    private MockRestServiceServer server;
    private NeutronClient client;

    @BeforeEach
    void setUp() {
        OpenStackProperties props = new OpenStackProperties();
        props.setNetworkUrl(NETWORK_URL);

        KeystoneClient keystone = mock(KeystoneClient.class);
        when(keystone.getToken()).thenReturn("tok-abc");

        RestClient.Builder builder = RestClient.builder().baseUrl(NETWORK_URL);
        server = MockRestServiceServer.bindTo(builder).build();
        client = new NeutronClient(props, keystone, builder.build());
    }

    @Test
    void list_networks_sends_auth_token_header() {
        server.expect(requestTo(NETWORK_URL + "/v2.0/networks"))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header("X-Auth-Token", "tok-abc"))
                .andRespond(withStatus(HttpStatus.OK)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"networks\":[{\"id\":\"net-1\",\"name\":\"private\"}]}"));

        Map<String, Object> result = client.listNetworks();

        assertThat(result).containsKey("networks");
        server.verify();
    }

    @Test
    void list_subnets_by_network_filters_by_network_id() {
        server.expect(requestTo(NETWORK_URL + "/v2.0/subnets?network_id=net-1"))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header("X-Auth-Token", "tok-abc"))
                .andRespond(withStatus(HttpStatus.OK)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"subnets\":[{\"id\":\"sub-1\",\"cidr\":\"10.0.1.0/24\"}]}"));

        Map<String, Object> result = client.listSubnetsByNetwork("net-1");

        assertThat(result).containsKey("subnets");
        server.verify();
    }

    @Test
    void get_port_returns_port_detail() {
        server.expect(requestTo(NETWORK_URL + "/v2.0/ports/port-1"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withStatus(HttpStatus.OK)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"port\":{\"id\":\"port-1\",\"fixed_ips\":[]}}"));

        Map<String, Object> result = client.getPort("port-1");

        assertThat(result).containsKey("port");
        server.verify();
    }

    @Test
    void unconfigured_network_url_throws_service_unavailable() {
        OpenStackProperties empty = new OpenStackProperties();
        NeutronClient unconfigured = new NeutronClient(empty, mock(KeystoneClient.class));

        assertThatThrownBy(unconfigured::listNetworks)
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getStatus())
                        .isEqualTo(HttpStatus.SERVICE_UNAVAILABLE));
    }
}
