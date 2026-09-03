package com.innogrid.aims.scheduler.dispatch;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 기록형 디스패처 — 외부를 부르지 않고 요청을 로그로 남기며 수락한다.
 *
 * <p>실행 계층 API 가 없는 지금의 기본값. 이 모드에서 워크로드는 SCHEDULED 에 머물고, 진행은 콜백 API 나 상태 변경 API 로
 * 외부(운영자·시연 스크립트)가 알려 준다. 응답 시간 상한 판정은 하지 않는다 — 응답이 올 곳이 없기 때문이다.</p>
 */
@Component
public class RecordingPlacementDispatcher implements PlacementDispatcher {

    public static final String MODE = "record";

    private static final Logger log = LoggerFactory.getLogger(RecordingPlacementDispatcher.class);

    @Override
    public String getMode() { return MODE; }

    @Override
    public Outcome dispatch(Request r) {
        log.info("실행 요청 기록: dispatch={}, workload={}({}), node={}/{}, cpu={}, mem={}MB, gpu={}, run={}",
                r.dispatchId(), r.workloadName(), r.workloadId(), r.mdcId(), r.nodeId(),
                r.requiredCpu(), r.requiredMemoryMb(), r.requiredGpu(), r.scheduleRunId());
        return Outcome.accepted(null, "기록됨 — 실행 계층 미연동(record 모드)");
    }
}
