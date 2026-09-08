package com.innogrid.aims.monitoring.log;

import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;

/** 요청 ID 필터 — 헤더 재사용/신규 발급, MDC 설정과 정리, 응답 헤더. */
class RequestIdFilterTest {

    RequestIdFilter filter = new RequestIdFilter();

    @Test
    void 클라이언트가_보낸_ID를_재사용하고_요청_뒤_MDC를_비운다() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(RequestIdFilter.HEADER, "client-abc-1");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<String> seenInChain = new AtomicReference<>();

        filter.doFilter(request, response, new MockFilterChain() {
            @Override
            public void doFilter(jakarta.servlet.ServletRequest req, jakarta.servlet.ServletResponse res) {
                seenInChain.set(MDC.get(RequestIdFilter.MDC_KEY));
            }
        });

        assertThat(seenInChain.get()).isEqualTo("client-abc-1");
        assertThat(response.getHeader(RequestIdFilter.HEADER)).isEqualTo("client-abc-1");
        assertThat(MDC.get(RequestIdFilter.MDC_KEY)).isNull();
    }

    @Test
    void 헤더가_없거나_형식이_이상하면_새로_발급한다() {
        assertThat(RequestIdFilter.incomingOrNew(null)).hasSize(36);
        assertThat(RequestIdFilter.incomingOrNew("  ")).hasSize(36);
        assertThat(RequestIdFilter.incomingOrNew("has space")).hasSize(36);
        assertThat(RequestIdFilter.incomingOrNew("x".repeat(65))).hasSize(36);
        assertThat(RequestIdFilter.incomingOrNew("ok_id.1:2-3")).isEqualTo("ok_id.1:2-3");
    }
}
