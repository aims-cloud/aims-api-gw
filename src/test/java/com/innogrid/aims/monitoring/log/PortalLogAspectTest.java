package com.innogrid.aims.monitoring.log;

import com.innogrid.aims.common.exception.BusinessException;
import com.innogrid.aims.common.log.PortalLog;
import com.innogrid.aims.monitoring.dto.log.PortalLogResult;
import com.innogrid.aims.monitoring.dto.log.PortalLogType;
import com.innogrid.aims.monitoring.entity.PortalLogEntry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.aop.aspectj.annotation.AspectJProxyFactory;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * @PortalLog Aspect — 성공/실패/async 판정, 공통 필드 채움, 내용 SpEL, 예외 재전파, 기록 표시 속성.
 * 컨텍스트 해석기·저장기는 대역, SpEL 평가기는 실제.
 */
class PortalLogAspectTest {

    record ZoneRequest(String name) {}
    record Job(String jobId) {}

    public static class ZoneController {
        @PortalLog(screen = "네트워크 구역", action = "생성", content = "'네트워크 구역 생성: ' + #request.name")
        public String create(ZoneRequest request) {
            if (request.name().isBlank()) {
                throw new BusinessException(HttpStatus.BAD_REQUEST, "이름은 비울 수 없습니다");
            }
            return "ok";
        }

        @PortalLog(screen = "네트워크 구역", action = "삭제", content = "'삭제: ' + #id")
        public void delete(long id) {
            throw new IllegalStateException("db down");
        }

        @PortalLog(type = PortalLogType.ACTION, async = true, screen = "가상머신", action = "생성",
                   content = "'Infra 배포: ' + #infraName", refType = "INFRA_JOB", refId = "#result.jobId")
        public Job deploy(String infraName) {
            return new Job("job-7");
        }

        public String plain() {
            return "no log";
        }
    }

    PortalLogContextResolver resolver = mock(PortalLogContextResolver.class);
    PortalLogWriter writer = mock(PortalLogWriter.class);
    ZoneController controller;
    MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/network-zones");

    @BeforeEach
    void setUp() {
        when(resolver.resolve()).thenReturn(new PortalLogContext("admin@innogrid.com", "관리자", "10.0.0.9", "req-1",
                "POST", "/api/v1/network-zones"));
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));

        AspectJProxyFactory factory = new AspectJProxyFactory(new ZoneController());
        factory.addAspect(new PortalLogAspect(resolver, new PortalLogSpelEvaluator(), writer));
        controller = factory.getProxy();
    }

    @Test
    void 정상_반환은_SUCCESS로_공통_필드와_내용을_채워_기록한다() {
        String result = controller.create(new ZoneRequest("zone-a"));

        assertThat(result).isEqualTo("ok");
        PortalLogEntry entry = written();
        assertThat(entry.getType()).isEqualTo(PortalLogType.AUDIT);
        assertThat(entry.getResult()).isEqualTo(PortalLogResult.SUCCESS);
        assertThat(entry.getScreen()).isEqualTo("네트워크 구역");
        assertThat(entry.getAction()).isEqualTo("생성");
        assertThat(entry.getContent()).isEqualTo("네트워크 구역 생성: zone-a");
        assertThat(entry.getUserId()).isEqualTo("admin@innogrid.com");
        assertThat(entry.getUserName()).isEqualTo("관리자");
        assertThat(entry.getClientIp()).isEqualTo("10.0.0.9");
        assertThat(entry.getRequestId()).isEqualTo("req-1");
        assertThat(entry.getHttpMethod()).isEqualTo("POST");
        assertThat(entry.getRequestUri()).isEqualTo("/api/v1/network-zones");
        assertThat(entry.getCompletedAt()).isNotNull();
        assertThat(entry.getDescription()).isNull();
        assertThat(request.getAttribute(PortalLogAspect.RECORDED_ATTRIBUTE)).isEqualTo(Boolean.TRUE);
    }

    @Test
    void 업무_예외는_FAILURE와_메시지로_기록하고_예외를_그대로_던진다() {
        assertThatThrownBy(() -> controller.create(new ZoneRequest("  ")))
                .isInstanceOf(BusinessException.class)
                .hasMessage("이름은 비울 수 없습니다");

        PortalLogEntry entry = written();
        assertThat(entry.getResult()).isEqualTo(PortalLogResult.FAILURE);
        assertThat(entry.getDescription()).isEqualTo("이름은 비울 수 없습니다");
        assertThat(entry.getContent()).isEqualTo("네트워크 구역 생성:   ");        // 실패 시에도 요청 인자로 내용을 만든다
    }

    @Test
    void 그_외_예외는_예외_종류를_함께_남긴다() {
        assertThatThrownBy(() -> controller.delete(7L)).isInstanceOf(IllegalStateException.class);

        PortalLogEntry entry = written();
        assertThat(entry.getResult()).isEqualTo(PortalLogResult.FAILURE);
        assertThat(entry.getDescription()).isEqualTo("IllegalStateException: db down");
        assertThat(entry.getContent()).isEqualTo("삭제: 7");
    }

    @Test
    void async는_IN_PROGRESS로_남기고_ref를_결과에서_뽑는다() {
        controller.deploy("openstack-infra-01");

        PortalLogEntry entry = written();
        assertThat(entry.getType()).isEqualTo(PortalLogType.ACTION);
        assertThat(entry.getResult()).isEqualTo(PortalLogResult.IN_PROGRESS);
        assertThat(entry.getCompletedAt()).isNull();
        assertThat(entry.getRefType()).isEqualTo("INFRA_JOB");
        assertThat(entry.getRefId()).isEqualTo("job-7");
        assertThat(entry.getContent()).isEqualTo("Infra 배포: openstack-infra-01");
    }

    @Test
    void 애너테이션이_없는_메서드는_기록하지_않는다() {
        controller.plain();

        verify(writer, org.mockito.Mockito.never()).write(any());
    }

    @Test
    void 요청_문맥이_없어도_기록한다() {
        RequestContextHolder.resetRequestAttributes();
        when(resolver.resolve()).thenReturn(PortalLogContext.EMPTY);

        controller.create(new ZoneRequest("zone-b"));

        PortalLogEntry entry = written();
        assertThat(entry.getUserId()).isNull();
        assertThat(entry.getContent()).isEqualTo("네트워크 구역 생성: zone-b");
    }

    private PortalLogEntry written() {
        ArgumentCaptor<PortalLogEntry> captor = ArgumentCaptor.forClass(PortalLogEntry.class);
        verify(writer).write(captor.capture());
        return captor.getValue();
    }
}
