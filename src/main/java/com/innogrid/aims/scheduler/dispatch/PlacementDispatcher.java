package com.innogrid.aims.scheduler.dispatch;

/**
 * 배치 결정을 실행 계층으로 넘기는 자리.
 *
 * <p>스케줄러는 판단 계층이다. 워크로드를 노드에 실제로 올리는 것은 실행 계층(1세부 API)이며, 그 API는 아직 정해지지
 * 않았다. 그래서 이 인터페이스가 경계다 — 지금은 {@link RecordingPlacementDispatcher}가 기록만 하고, 실행 API가 정해지면
 * 원격 구현체를 추가해 설정({@code aims.scheduler.dispatch.mode})으로 고른다.</p>
 *
 * <p><b>1세부 실행 API에 대한 가정</b>(구현체가 지켜야 할 계약):
 * <ol>
 *   <li>실행 요청은 비동기로 수락되고 즉시 식별자(externalRef)를 돌려준다.</li>
 *   <li>진행 결과(RUNNING·COMPLETED·FAILED)는 우리 콜백 {@code POST /api/v1/scheduler/dispatches/{id}/result}로 온다.</li>
 *   <li>식별은 우리 dispatch id로 하며 멱등키를 함께 보낸다.</li>
 *   <li>콜백 호출자는 JWT 서비스 계정을 쓴다(운영 설정).</li>
 * </ol>
 * 구현체는 예외를 던지지 않고 {@link Outcome}으로 결과를 돌려주는 것이 원칙이다. 던지면 서비스가 UNAVAILABLE로 기록한다.</p>
 */
public interface PlacementDispatcher {

    /** 설정 {@code mode} 값과 같은 식별자. */
    String getMode();

    Outcome dispatch(Request request);

    /**
     * 운영자가 배치 예약을 취소했다 — 이미 수락(ACCEPTED)한 결정을 실행 계층에 되돌리라고 알리는 자리.
     *
     * <p>기록형 디스패처는 실행 계층이 없어 할 일이 없고, 원격 구현체가 생기면 여기서 취소 요청을 보낸다. 기본은 아무
     * 것도 하지 않는다. 예외를 던지지 않는 것이 원칙이다 — 던져도 서비스는 취소를 계속하고 전달 기록 비고에 남긴다.</p>
     *
     * <p><b>호출자의 DB 트랜잭션 안에서 불린다</b>({@code afterPlacement}의 디스패치 호출이 트랜잭션 밖인 것과 다르다).
     * 원격 구현체는 여기서 곧바로 외부 호출을 하지 말고 커밋 뒤({@code TransactionSynchronization.afterCommit})로
     * 미뤄야 한다 — 그러지 않으면 롤백 때 원격은 취소됐는데 DB는 그대로인 상태가 된다.</p>
     */
    default void cancel(Request request) {}

    /** 실행 계층에 넘길 내용. 결정 자체는 이미 저장된 뒤라 여기서는 읽기만 한다. */
    record Request(Long dispatchId, String idempotencyKey,
                   Long workloadId, String workloadName, String resourceType,
                   int requiredCpu, int requiredMemoryMb, int requiredGpu, int requiredDiskGb,
                   String mdcId, String nodeId, Double score, String reason, Long scheduleRunId) {}

    /** 결과. {@code ACCEPTED}만 진행하고 나머지는 워크로드를 FAILED로 되돌린다. */
    record Outcome(Kind kind, String externalRef, String message) {

        public enum Kind { ACCEPTED, REJECTED, UNAVAILABLE }

        public static Outcome accepted(String externalRef, String message) { return new Outcome(Kind.ACCEPTED, externalRef, message); }
        public static Outcome rejected(String message) { return new Outcome(Kind.REJECTED, null, message); }
        public static Outcome unavailable(String message) { return new Outcome(Kind.UNAVAILABLE, null, message); }

        public boolean accepted() { return kind == Kind.ACCEPTED; }
    }
}
