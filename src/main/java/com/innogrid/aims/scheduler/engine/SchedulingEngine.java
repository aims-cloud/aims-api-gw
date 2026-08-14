package com.innogrid.aims.scheduler.engine;

import java.util.List;

import com.innogrid.aims.scheduler.entity.SchedulingPolicy;
import com.innogrid.aims.scheduler.entity.Workload;
import com.innogrid.aims.scheduler.model.NodeResource;
import com.innogrid.aims.scheduler.model.SchedulingResult;

/**
 * 스케줄링 엔진 인터페이스.
 * 워크로드와 후보 노드 목록을 받아 최적의 노드를 선택한다.
 */
public interface SchedulingEngine {

    /**
     * 워크로드를 최적 노드에 배치한다.
     *
     * @param workload   배치할 워크로드 (자원 요구사항 포함)
     * @param candidates 후보 노드 목록 (실시간 자원 상태)
     * @param policy     스케줄링 정책 (가중치, 제약 조건)
     * @return 스케줄링 결과 (선택된 노드, 점수, 근거)
     */
    SchedulingResult schedule(Workload workload, List<NodeResource> candidates, SchedulingPolicy policy);

    /**
     * 엔진 이름.
     */
    String getName();
}
