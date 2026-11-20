package com.innogrid.aims.monitoring.log;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import com.innogrid.aims.monitoring.dto.log.PortalLogResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 외부 연동·내부 배치의 장애 상태 추적 — 실패·성공 관측을 받아 <b>상태가 바뀔 때만</b> 포털 로그 SYSTEM 행을 남긴다
 * (docs/08 §6 "SYSTEM 은 상태 변화가 있을 때만 기록한다").
 *
 * <ul>
 *   <li>정상 → 실패: {@code FAILURE} 행 1건(원인 포함). 이후 같은 단위가 매 주기 반복 실패해도 다시 쓰지 않는다.</li>
 *   <li>실패 → 정상: {@code SUCCESS} 행 1건("복구").</li>
 *   <li>키는 장애 단위 — 소유자가 정한다(Thanos {@code query}, Tumblebug {@code ns}·{@code infra:{nsId}}, 알람 {@code metric:…}).</li>
 * </ul>
 * 인스턴스 하나가 화면({@code screen}) 하나를 맡는다. 상태는 메모리에만 두므로 재기동 직후 첫 실패는 다시 한 번 기록된다
 * (의도한 동작 — 재기동 뒤에도 장애가 이어짐을 알려야 한다). 기록 실패는 삼키고 상태만 바꾼다.
 */
public class IntegrationHealth {

    private static final Logger log = LoggerFactory.getLogger(IntegrationHealth.class);

    private final PortalLogService portalLogService;
    private final String screen;
    /** 현재 실패 중인 장애 단위 → 마지막 원인 */
    private final Map<String, String> failing = new ConcurrentHashMap<>();

    public IntegrationHealth(PortalLogService portalLogService, String screen) {
        this.portalLogService = portalLogService;
        this.screen = screen;
    }

    /** 실패 관측 — 처음이면 SYSTEM FAILURE 기록, 반복이면 원인만 갱신 */
    public void failed(String key, String what, Throwable cause) {
        String reason = cause == null || cause.getMessage() == null ? String.valueOf(cause) : cause.getMessage();
        String previous = failing.put(key, reason);
        if (previous != null) {
            log.debug("[{}] 장애 지속: key={}, error={}", screen, key, reason);
            return;
        }
        log.warn("[{}] 장애 시작: key={}, what={}, error={}", screen, key, what, reason);
        record(what + " 실패", PortalLogResult.FAILURE, reason);
    }

    /** 성공 관측 — 실패 중이었으면 SYSTEM SUCCESS("복구") 기록 */
    public void recovered(String key, String what) {
        if (failing.remove(key) == null) return;
        log.info("[{}] 장애 복구: key={}, what={}", screen, key, what);
        record(what + " 복구", PortalLogResult.SUCCESS, null);
    }

    /** 현재 실패 중인 장애 단위(테스트·상태 노출용) */
    public Set<String> failingKeys() {
        return Set.copyOf(failing.keySet());
    }

    private void record(String content, PortalLogResult result, String description) {
        try {
            portalLogService.system(screen, result == PortalLogResult.FAILURE ? "장애" : "복구", content, result, description);
        } catch (RuntimeException e) {
            log.warn("[{}] 상태 포털 로그 기록 실패: {}", screen, e.getMessage());
        }
    }
}
