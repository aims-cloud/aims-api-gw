package com.innogrid.aims.monitoring;

import com.innogrid.aims.monitoring.dto.log.PortalLogResult;
import com.innogrid.aims.monitoring.log.PortalLogService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.ExpectedCount;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * Thanos 연동 상태 → 포털 로그 SYSTEM (docs/08 §6) — 5xx·429 는 정상→실패 전이에서 FAILURE 1건, 반복 실패는 없음,
 * 다음 성공에서 복구 1건. 잘못된 쿼리(400)는 연동 장애가 아니라 상태를 바꾸지 않는다. 예외는 호출자에게 그대로 전달된다.
 */
@ExtendWith(MockitoExtension.class)
class VictoriaMetricsClientHealthTest {

    static final String OK = "{\"status\":\"success\",\"data\":{\"resultType\":\"vector\",\"result\":[]}}";

    @Mock PortalLogService portalLogService;
    MockRestServiceServer server;
    VictoriaMetricsClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://thanos.test");
        server = MockRestServiceServer.bindTo(builder).build();
        client = new VictoriaMetricsClient(builder.build(), portalLogService);
    }

    @Test
    void 정상_조회는_기록하지_않고_5xx_시작과_다음_성공_복구를_각_1건_남긴다() {
        server.expect(ExpectedCount.times(1), requestTo(org.hamcrest.Matchers.startsWith("http://thanos.test/api/v1/query")))
                .andRespond(withSuccess(OK, MediaType.APPLICATION_JSON));
        server.expect(ExpectedCount.times(2), requestTo(org.hamcrest.Matchers.startsWith("http://thanos.test/api/v1/query")))
                .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));
        server.expect(ExpectedCount.times(1), requestTo(org.hamcrest.Matchers.startsWith("http://thanos.test/api/v1/query")))
                .andRespond(withSuccess(OK, MediaType.APPLICATION_JSON));

        assertThat(client.queryResults("up")).isEmpty();                                  // 정상 → 정상: 없음
        verify(portalLogService, never()).system(anyString(), anyString(), anyString(), any(), any());

        assertThatThrownBy(() -> client.query("up")).isInstanceOf(HttpServerErrorException.class);      // 정상 → 실패
        assertThatThrownBy(() -> client.query("up")).isInstanceOf(HttpServerErrorException.class);      // 실패 지속
        verify(portalLogService, times(1)).system(eq("Thanos 연동"), eq("장애"), eq("Thanos 조회 실패"),
                eq(PortalLogResult.FAILURE), anyString());

        client.query("up");                                                                             // 실패 → 정상
        verify(portalLogService, times(1)).system(eq("Thanos 연동"), eq("복구"), eq("Thanos 조회 복구"),
                eq(PortalLogResult.SUCCESS), isNull());
        server.verify();
    }

    @Test
    void 잘못된_쿼리_400_은_연동_장애로_보지_않고_예외만_전달한다() {
        server.expect(requestTo(org.hamcrest.Matchers.startsWith("http://thanos.test/api/v1/query")))
                .andRespond(withStatus(HttpStatus.BAD_REQUEST).contentType(MediaType.APPLICATION_JSON)
                        .body("{\"status\":\"error\",\"error\":\"parse error\"}"));

        assertThatThrownBy(() -> client.query("up{")).isInstanceOf(HttpClientErrorException.BadRequest.class);

        verify(portalLogService, never()).system(anyString(), anyString(), anyString(), any(), any());
    }

    @Test
    void 속도_제한_429_는_장애로_기록한다() {
        server.expect(requestTo(org.hamcrest.Matchers.startsWith("http://thanos.test/api/v1/query_range")))
                .andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS));

        assertThatThrownBy(() -> client.queryRange("up", java.time.Instant.EPOCH, java.time.Instant.EPOCH.plusSeconds(60), "15s"))
                .isInstanceOf(HttpClientErrorException.TooManyRequests.class);

        verify(portalLogService).system(eq("Thanos 연동"), eq("장애"), eq("Thanos 조회 실패"), eq(PortalLogResult.FAILURE), anyString());
    }
}
