package com.innogrid.aims.monitoring.service.alarm;

import java.util.Set;

import com.innogrid.aims.monitoring.log.IntegrationHealth;
import com.innogrid.aims.monitoring.log.PortalLogService;
import org.springframework.stereotype.Component;

/**
 * 알람 평가기 장애 상태 추적 — Thanos 조회·역할 스코프 조회 실패를 <b>상태가 바뀔 때만</b> 포털 로그 SYSTEM 으로 남긴다
 * (docs/08 §2 SYSTEM, docs/09 C.2 "예외를 log.debug 로 삼킴" 대체). 전이 규칙은 {@link IntegrationHealth} 에 있고,
 * 여기서는 화면명("알람 평가기")과 장애 단위 키(역할 스코프 {@code scope}, 메트릭별 {@code metric:CPU_USED_PERCENT})만 정한다.
 */
@Component
public class AlarmEvaluationHealth {

    static final String SCREEN = "알람 평가기";
    public static final String SCOPE_KEY = "scope";

    private final IntegrationHealth health;

    public AlarmEvaluationHealth(PortalLogService portalLogService) {
        this.health = new IntegrationHealth(portalLogService, SCREEN);
    }

    public static String metricKey(AlarmMetric metric) {
        return "metric:" + metric.name();
    }

    /** 실패 관측 — 처음이면 SYSTEM FAILURE 기록, 반복이면 원인만 갱신 */
    public void failed(String key, String what, Throwable cause) {
        health.failed(key, what, cause);
    }

    /** 성공 관측 — 실패 중이었으면 SYSTEM SUCCESS("복구") 기록 */
    public void recovered(String key, String what) {
        health.recovered(key, what);
    }

    /** 현재 실패 중인 장애 단위(테스트·상태 노출용) */
    public Set<String> failingKeys() {
        return health.failingKeys();
    }
}
