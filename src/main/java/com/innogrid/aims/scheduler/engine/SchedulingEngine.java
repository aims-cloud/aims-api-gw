package com.innogrid.aims.scheduler.engine;

import java.util.List;

import com.innogrid.aims.scheduler.engine.constraint.PlacementContext;
import com.innogrid.aims.scheduler.entity.SchedulingPolicy;
import com.innogrid.aims.scheduler.entity.Workload;
import com.innogrid.aims.scheduler.model.NodeResource;
import com.innogrid.aims.scheduler.model.SchedulingResult;

/**
 * 스케줄링 엔진 인터페이스.
 * 워크로드와 후보 노드 목록을 받아 제약을 통과한 후보 중 점수가 가장 높은 노드를 선택한다.
 */
public interface SchedulingEngine {

    /**
     * 워크로드를 제약을 통과한 후보 중 점수가 가장 높은 노드에 배치한다.
     *
     * @param workload 배치할 워크로드 (자원 요구사항 포함)
     * @param context  배치 컨텍스트 (노드 상태, 정책, 예약 현황)
     * @return 스케줄링 결과 (선택된 노드, 점수, 근거)
     */
    SchedulingResult schedule(Workload workload, PlacementContext context);

    /**
     * 후보 목록과 정책만으로 호출하는 형태.
     * 예약 정보가 없는 단발 호출에 쓴다.
     */
    default SchedulingResult schedule(Workload workload, List<NodeResource> candidates, SchedulingPolicy policy) {
        return schedule(workload, PlacementContext.of(candidates, policy));
    }

    /**
     * 엔진 이름.
     */
    String getName();

    /**
     * 엔진 선택에 쓰는 식별자. 요청 파라미터의 engine 값과 대응한다.
     */
    String getId();
}
