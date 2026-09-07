package com.innogrid.aims.monitoring.log;

import com.innogrid.aims.common.log.PortalLog;
import com.innogrid.aims.monitoring.dto.log.PortalLogResult;
import com.innogrid.aims.monitoring.entity.PortalLogEntry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.method.HandlerMethod;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 진입 전 실패 보조 경로 — @PortalLog 핸들러 + 4xx + Aspect 미기록일 때만 FAILURE 고정 문구로 남긴다. */
class PortalLogFallbackInterceptorTest {

    public static class ZoneController {
        @PortalLog(screen = "네트워크 구역", action = "생성", content = "'x'")
        public void create(Object request) {}

        public void list() {}
    }

    PortalLogContextResolver resolver = mock(PortalLogContextResolver.class);
    PortalLogWriter writer = mock(PortalLogWriter.class);
    PortalLogFallbackInterceptor interceptor = new PortalLogFallbackInterceptor(resolver, writer);
    MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/network-zones");
    MockHttpServletResponse response = new MockHttpServletResponse();

    @BeforeEach
    void setUp() {
        when(resolver.resolve()).thenReturn(new PortalLogContext("admin", "관리자", "10.0.0.9", "req-1", "POST", "/api/v1/network-zones"));
    }

    @Test
    void 검증_실패_400은_고정_문구로_FAILURE_기록() throws Exception {
        response.setStatus(400);

        interceptor.afterCompletion(request, response, handler("create", Object.class), null);

        ArgumentCaptor<PortalLogEntry> captor = ArgumentCaptor.forClass(PortalLogEntry.class);
        verify(writer).write(captor.capture());
        PortalLogEntry entry = captor.getValue();
        assertThat(entry.getResult()).isEqualTo(PortalLogResult.FAILURE);
        assertThat(entry.getContent()).isEqualTo("네트워크 구역 생성 요청 검증 실패");
        assertThat(entry.getDescription()).isEqualTo("HTTP 400");
        assertThat(entry.getUserId()).isEqualTo("admin");
    }

    @Test
    void 미해결_예외가_넘어오면_그_메시지를_남긴다() throws Exception {
        response.setStatus(500);

        interceptor.afterCompletion(request, response, handler("create", Object.class), new IllegalArgumentException("bad json"));

        ArgumentCaptor<PortalLogEntry> captor = ArgumentCaptor.forClass(PortalLogEntry.class);
        verify(writer).write(captor.capture());
        assertThat(captor.getValue().getDescription()).isEqualTo("IllegalArgumentException: bad json");
    }

    @Test
    void Aspect가_이미_기록했거나_성공_응답이거나_애너테이션이_없으면_기록하지_않는다() throws Exception {
        response.setStatus(400);
        request.setAttribute(PortalLogAspect.RECORDED_ATTRIBUTE, Boolean.TRUE);
        interceptor.afterCompletion(request, response, handler("create", Object.class), null);

        MockHttpServletRequest fresh = new MockHttpServletRequest();
        MockHttpServletResponse ok = new MockHttpServletResponse();
        ok.setStatus(200);
        interceptor.afterCompletion(fresh, ok, handler("create", Object.class), null);

        interceptor.afterCompletion(fresh, response, handler("list"), null);
        interceptor.afterCompletion(fresh, response, "not a handler method", null);

        verify(writer, never()).write(any());
    }

    private static HandlerMethod handler(String name, Class<?>... params) throws NoSuchMethodException {
        return new HandlerMethod(new ZoneController(), ZoneController.class.getMethod(name, params));
    }
}
