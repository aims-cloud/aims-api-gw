package com.innogrid.aims.monitoring.service.alarm;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import com.innogrid.aims.monitoring.dto.log.PortalLogResult;
import com.innogrid.aims.monitoring.log.PortalLogService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 알람 평가기 장애 상태 추적 — Thanos 조회·역할 스코프 조회 실패를 <b>상태가 바뀔 때만</b> 포털 로그 SYSTEM 으로 남긴다
 * (docs/08 §2 SYSTEM, docs/09 C.2 "예외를 log.debug 로 삼킴" 대체).
 *
 * <ul>
 *   <li>정상 → 실패: {@code FAILURE} 행 1건(원인 포함). 이후 같은 원인이 매 주기 반복돼도 다시 쓰지 않는다.</li>
 *   <li>실패 → 정상: {@code SUCCESS} 행 1건("복구").</li>
 *   <li>키는 장애 단위 — 역할 스코프({@code scope}), 메트릭별 Thanos 조회({@code metric:CPU_USED_PERCENT}).</li>
 * </ul>
 * 상태는 메모리에만 두므로 재기동 직후 첫 실패는 다시 한 번 기록된다(의도한 동작 — 재기동 뒤에도 장애가 이어짐을 알려야 한다).
 */
@Component
public class AlarmEvaluationHealth {

    private static final Logger log = LoggerFactory.getLogger(AlarmEvaluationHealth.class);

    static final String SCREEN = "알람 평가기";
    public static final String SCOPE_KEY = "scope";

    private final PortalLogService portalLogService;
    /** 현재 실패 중인 장애 단위 → 마지막 원인 */
    private final Map<String, String> failing = new ConcurrentHashMap<>();

    public AlarmEvaluationHealth(PortalLogService portalLogService) {
        this.portalLogService = portalLogService;
    }

    public static String metricKey(AlarmMetric metric) {
        return "metric:" + metric.name();
    }

    /** 실패 관측 — 처음이면 SYSTEM FAILURE 기록, 반복이면 원인만 갱신 */
    public void failed(String key, String what, Throwable cause) {
        String reason = cause == null || cause.getMessage() == null ? String.valueOf(cause) : cause.getMessage();
        String previous = failing.put(key, reason);
        if (previous != null) {
            log.debug("알람 평가 장애 지속: key={}, error={}", key, reason);
            return;
        }
        log.warn("알람 평가 장애 시작: key={}, what={}, error={}", key, what, reason);
        record(what + " 실패", PortalLogResult.FAILURE, reason);
    }

    /** 성공 관측 — 실패 중이었으면 SYSTEM SUCCESS("복구") 기록 */
    public void recovered(String key, String what) {
        if (failing.remove(key) == null) return;
        log.info("알람 평가 장애 복구: key={}, what={}", key, what);
        record(what + " 복구", PortalLogResult.SUCCESS, null);
    }

    /** 현재 실패 중인 장애 단위(테스트·상태 노출용) */
    public Set<String> failingKeys() {
        return Set.copyOf(failing.keySet());
    }

    private void record(String content, PortalLogResult result, String description) {
        try {
            portalLogService.system(SCREEN, result == PortalLogResult.FAILURE ? "장애" : "복구", content, result, description);
        } catch (RuntimeException e) {
            log.warn("알람 평가기 상태 포털 로그 기록 실패: {}", e.getMessage());
        }
    }
}
