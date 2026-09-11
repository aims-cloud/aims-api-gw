package com.innogrid.aims.monitoring;

import java.time.Instant;
import com.innogrid.aims.common.exception.BusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

class LokiQueryClientTest {
    MockRestServiceServer server;
    LokiQueryClient client;
    Instant time = Instant.parse("2026-09-11T00:00:00Z");
    @BeforeEach void setup() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://loki");
        server = MockRestServiceServer.bindTo(builder).build();
        client = new LokiQueryClient(builder.build());
    }
    @Test void parsesVectorAndPassesExplicitTime() {
        server.expect(request -> assertThat(request.getURI().toString()).contains("/query?", "time=2026-09-11"))
                .andRespond(withSuccess("{\"status\":\"success\",\"data\":{\"resultType\":\"vector\",\"result\":[{\"metric\":{},\"value\":[1789084800,\"7\"]}]}}", MediaType.APPLICATION_JSON));
        assertThat(client.instant("sum(count_over_time({job=\"a\"}[5m]))", time).get(0).count()).isEqualTo(7);
        server.verify();
    }
    @Test void parsesMatrixNumericTimestamps() {
        server.expect(request -> assertThat(request.getURI().toString()).contains("step=60"))
                .andRespond(withSuccess("{\"status\":\"success\",\"data\":{\"resultType\":\"matrix\",\"result\":[{\"metric\":{\"level\":\"WARN\"},\"values\":[[1789084800,\"2\"],[1789084860,\"3\"]]}]}}", MediaType.APPLICATION_JSON));
        assertThat(client.range("query", time, time.plusSeconds(60), 60)).hasSize(2);
        server.verify();
    }
    @Test void streamNanosecondsArePreserved() {
        server.expect(request -> assertThat(request.getURI().toString()).contains("direction=backward"))
                .andRespond(withSuccess("{\"status\":\"success\",\"data\":{\"resultType\":\"streams\",\"result\":[{\"stream\":{\"filename\":\"/a\"},\"values\":[[\"1789084800000000001\",\"line\"]]}]}}", MediaType.APPLICATION_JSON));
        assertThat(client.logs("query", time, time.plusSeconds(60), 1).get(0).time().getNano()).isEqualTo(1);
        server.verify();
    }
    @Test void wrongResultTypeIsNotEmptySuccess() {
        server.expect(request -> {}).andRespond(withSuccess("{\"status\":\"success\",\"data\":{\"resultType\":\"streams\",\"result\":[]}}", MediaType.APPLICATION_JSON));
        assertThatThrownBy(() -> client.instant("query", time)).isInstanceOf(BusinessException.class);
    }
    @Test void remoteErrorIsNotEmptySuccess() {
        server.expect(request -> {}).andRespond(withServerError());
        assertThatThrownBy(() -> client.instant("query", time)).isInstanceOf(BusinessException.class);
    }
}
