package com.innogrid.aims.monitoring.log;

import java.time.OffsetDateTime;

import com.innogrid.aims.monitoring.dto.log.PortalLogResult;
import com.innogrid.aims.monitoring.dto.log.PortalLogType;
import com.innogrid.aims.monitoring.entity.PortalLogEntry;
import org.springframework.stereotype.Service;

/**
 * 다른 도메인이 포털 로그를 직접 남길 때 쓰는 진입점 (docs/08 §6·§7).
 *
 * 요청 단위 기록은 {@code @PortalLog} 애너테이션이 맡고, 이 서비스는 요청이 없는 곳(스케줄러·알람 엔진·인증 핸들러)과
 * ACTION 작업 완료 갱신에 쓴다. 저장 실패는 {@link PortalLogWriter} 가 삼키므로 호출자는 예외를 신경 쓰지 않아도 된다.
 */
@Service
public class PortalLogService {

    private final PortalLogWriter writer;
    private final PortalLogContextResolver contextResolver;

    public PortalLogService(PortalLogWriter writer, PortalLogContextResolver contextResolver) {
        this.writer = writer;
        this.contextResolver = contextResolver;
    }

    /** 만들어 둔 행을 그대로 저장 */
    public void record(PortalLogEntry entry) {
        writer.write(entry);
    }

    /** SYSTEM — 백엔드 내부 동작·외부 연동 상태. 상태 변화가 있을 때만 호출한다 */
    public void system(String screen, String action, String content, PortalLogResult result, String description) {
        writer.write(PortalLogEntry.builder(PortalLogType.SYSTEM)
                .startedAt(OffsetDateTime.now()).completedAt(OffsetDateTime.now())
                .screen(screen).action(action).content(content)
                .result(result).description(description)
                .build());
    }

    /** ALARM — 알람 발생·해소·확인 요약 행. 원장은 alarm_history, 여기엔 ref 만. 시각은 지금 */
    public void alarm(String action, String content, long alarmHistoryId) {
        alarm(action, content, alarmHistoryId, OffsetDateTime.now());
    }

    /** ALARM — 발생·해소처럼 전이 시각이 따로 있으면 그 시각을 started/completed 로 남긴다 */
    public void alarm(String action, String content, long alarmHistoryId, OffsetDateTime at) {
        PortalLogContext ctx = contextResolver.resolve();          // 확인(ack)은 사용자가 있고, 발생·해소는 없다
        OffsetDateTime when = at == null ? OffsetDateTime.now() : at;
        writer.write(PortalLogEntry.builder(PortalLogType.ALARM)
                .startedAt(when).completedAt(when)
                .user(ctx.userId(), ctx.userName()).clientIp(ctx.clientIp()).requestId(ctx.requestId())
                .screen("알람").action(action).content(content)
                .ref("ALARM_HISTORY", String.valueOf(alarmHistoryId))
                .build());
    }

    /** AUTH — 인증 이벤트. 인증 주체가 아직 없는 로그인 시도는 userId 를 직접 넘긴다 */
    public void auth(String userId, String action, String content, PortalLogResult result, String description) {
        PortalLogContext ctx = contextResolver.resolveAs(userId);
        writer.write(PortalLogEntry.builder(PortalLogType.AUTH)
                .startedAt(OffsetDateTime.now()).completedAt(OffsetDateTime.now())
                .user(ctx.userId(), ctx.userName()).clientIp(ctx.clientIp()).requestId(ctx.requestId())
                .request(ctx.httpMethod(), ctx.requestUri())
                .screen("로그인").action(action).content(content)
                .result(result).description(description)
                .build());
    }

    /** ACTION 완료 — IN_PROGRESS 행을 최종 결과로 갱신. 대상이 없으면 false */
    public boolean complete(String refType, String refId, PortalLogResult result, String description) {
        return writer.complete(refType, refId, result, description);
    }
}
