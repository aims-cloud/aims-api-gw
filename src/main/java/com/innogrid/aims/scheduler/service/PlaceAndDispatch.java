package com.innogrid.aims.scheduler.service;

import java.util.function.Supplier;
import java.util.function.UnaryOperator;

import com.innogrid.aims.scheduler.engine.constraint.PlacementContext;
import com.innogrid.aims.scheduler.entity.Workload;
import com.innogrid.aims.scheduler.model.SchedulingResult;

/**
 * 배치 결정과 그 뒤의 예약·전달·되돌림 순서. 스케줄 실행과 실행 재생이 함께 쓴다.
 *
 * <p>순서는 결정 → 성공이면 예약 → 전달 → 성공한 결정을 실행 계층이 받지 않았으면 예약 되돌림이다. 실행 루프와 재생이
 * {@link #run} 하나를 거치므로 둘의 예약·되돌림 순서가 갈리지 않고, 운영 코드에서 예약 되돌림
 * ({@link PlacementContext#unreserve})을 부르는 곳은 이 클래스뿐이다({@code PlaceAndDispatchTest} 가 확인한다).</p>
 *
 * <p>예약은 결정이 커밋된 뒤에 잡는다. 배치 저장 안에서 잡으면 저장·커밋이 실패해도 예약이 남아, 그 예외로 루프가 끝난 뒤
 * 남기는 실행 후 스냅샷에 저장되지 않은 배치가 반영된다. 실행 계층이 받지 않은 배치는 전달이 DB 의 워크로드를 이미 FAILED 로
 * 돌렸으므로, 같은 실행의 다음 워크로드와 실행 후 스냅샷이 없는 예약을 보지 않도록 여기서 예약을 되돌린다.</p>
 *
 * <p><b>빈이 아니고 트랜잭션을 열지 않는다.</b> 결정 저장({@link PlacementService#place})과 전달 기록은 각자 짧은
 * 트랜잭션에서 커밋된다. 둘을 한 트랜잭션으로 감싸면 전달 기록 마감이 실패할 때 결정까지 되돌아가고, 디스패처가 외부 시스템을
 * 기다리는 동안 조건부 갱신의 행 잠금이 남는다. 루프의 배치 직전 다시 읽기까지 감싸면, 조건부 갱신(영속성 컨텍스트를 비우지
 * 않음) 뒤 배치 저장 안의 다시 읽기가 그 트랜잭션에 남은 옛 사본을 돌려줘 재시도 횟수 이어받기가 깨진다.</p>
 *
 * <p><b>전달이 예외를 내면 예약을 되돌리지 않는다.</b> 결정은 이미 커밋됐으므로 그 예약은 실행 후 스냅샷에 남는 것이
 * 사실이다.</p>
 */
final class PlaceAndDispatch {

    private PlaceAndDispatch() {
    }

    /** 결정 결과와, 전달을 거친 결과(실행 계층이 받지 않았으면 실패로 낮아진 것). */
    record Done(SchedulingResult placed, SchedulingResult delivered) {}

    /**
     * 결정 → 예약 → 전달 → 되돌림을 한 번 거친다.
     *
     * @param decide  배치 결정. 실행은 {@link PlacementService#place}, 재생은 엔진 재계산
     * @param deliver 결정을 실행 계층에 넘긴 결과. 실행은 {@link SchedulingService#dispatch}, 재생은 실행이 예약을 되돌린 워크로드면 실패
     */
    static Done run(PlacementContext context, Workload workload,
                    Supplier<SchedulingResult> decide, UnaryOperator<SchedulingResult> deliver) {
        SchedulingResult placed = decide.get();
        Reservation held = placed.success() ? Reservation.hold(context, placed.selectedNodeId(), workload) : null;
        SchedulingResult delivered = deliver.apply(placed);
        if (held != null && !delivered.success()) {
            held.release();
        }
        return new Done(placed, delivered);
    }

    /** 잡은 예약 하나. 잡을 때의 노드와 워크로드로 되돌린다. */
    private static final class Reservation {

        private final PlacementContext context;
        private final String nodeId;
        private final Workload workload;

        private Reservation(PlacementContext context, String nodeId, Workload workload) {
            this.context = context;
            this.nodeId = nodeId;
            this.workload = workload;
        }

        static Reservation hold(PlacementContext context, String nodeId, Workload workload) {
            context.reserve(nodeId, workload);
            return new Reservation(context, nodeId, workload);
        }

        void release() {
            context.unreserve(nodeId, workload);
        }
    }
}
