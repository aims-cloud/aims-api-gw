package com.innogrid.aims.scheduler.dispatch;

/**
 * 배치 결정을 실행 계층으로 넘기는 자리.
 *
 * <p>스케줄러는 판단 계층이다. 워크로드를 노드에 실제로 올리는 것은 실행 계층(1세부 API)이며, 그 API 는 아직 정해지지
 * 않았다. 그래서 이 인터페이스가 경계다 — 지금은 {@link RecordingPlacementDispatcher} 가 기록만 하고, 실행 API 가 정해지면
 * 원격 구현체를 추가해 설정({@code aims.scheduler.dispatch.mode})으로 고른다.</p>
 *
 * <p><b>1세부 실행 API 에 대한 가정</b>(구현체가 지켜야 할 계약):
 * <ol>
 *   <li>실행 요청은 비동기로 수락되고 즉시 식별자(externalRef)를 돌려준다.</li>
 *   <li>진행 결과(RUNNING·COMPLETED·FAILED)는 우리 콜백 {@code POST /api/v1/scheduler/dispatches/{id}/result} 로 온다.</li>
 *   <li>식별은 우리 dispatch id 로 하며 멱등키를 함께 보낸다.</li>
 *   <li>콜백 호출자는 JWT 서비스 계정을 쓴다(운영 설정).</li>
 * </ol>
 * 구현체는 예외를 던지지 않고 {@link Outcome} 으로 결과를 돌려주는 것이 원칙이다. 던지면 서비스가 UNAVAILABLE 로 기록한다.</p>
 */
public interface PlacementDispatcher {

    /** 설정 {@code mode} 값과 같은 식별자. */
    String getMode();

    Outcome dispatch(Request request);

    /** 실행 계층에 넘길 내용. 결정 자체는 이미 저장된 뒤라 여기서는 읽기만 한다. */
    record Request(Long dispatchId, String idempotencyKey,
                   Long workloadId, String workloadName, String resourceType,
                   int requiredCpu, int requiredMemoryMb, int requiredGpu, int requiredDiskGb,
                   String mdcId, String nodeId, Double score, String reason, Long scheduleRunId) {}

    /** 결과. {@code ACCEPTED} 만 진행하고 나머지는 워크로드를 FAILED 로 되돌린다. */
    record Outcome(Kind kind, String externalRef, String message) {

        public enum Kind { ACCEPTED, REJECTED, UNAVAILABLE }

        public static Outcome accepted(String externalRef, String message) { return new Outcome(Kind.ACCEPTED, externalRef, message); }
        public static Outcome rejected(String message) { return new Outcome(Kind.REJECTED, null, message); }
        public static Outcome unavailable(String message) { return new Outcome(Kind.UNAVAILABLE, null, message); }

        public boolean accepted() { return kind == Kind.ACCEPTED; }
    }
}
