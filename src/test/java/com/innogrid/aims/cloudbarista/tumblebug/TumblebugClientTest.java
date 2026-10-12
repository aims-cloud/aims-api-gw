package com.innogrid.aims.cloudbarista.tumblebug;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/** VM 한 대 원격 명령 — baseUrl 아래 경로에 nodeId 쿼리가 붙고 본문이 그대로 간다(Tumblebug PostCmdInfra). */
class TumblebugClientTest {

    @Test void nodeCommandTargetsSingleNodeByQueryParameter() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://tb:1323/tumblebug");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        TumblebugClient client = new TumblebugClient(builder.build());

        server.expect(requestTo("http://tb:1323/tumblebug/ns/ns-1/cmd/infra/infra-1?nodeId=g1-1"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(jsonPath("$.command[0]").value("echo hi"))
                .andExpect(jsonPath("$.timeoutMinutes").value(15))
                .andRespond(withSuccess("{\"results\":[{\"nodeId\":\"g1-1\",\"error\":\"\"}]}", MediaType.APPLICATION_JSON));

        Map<String, Object> response = client.sendNodeCommand("ns-1", "infra-1", "g1-1",
                Map.of("command", List.of("echo hi"), "timeoutMinutes", 15));

        assertThat(response).containsKey("results");
        server.verify();
    }
}
