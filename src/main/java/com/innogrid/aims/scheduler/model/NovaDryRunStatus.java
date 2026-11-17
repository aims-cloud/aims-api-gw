package com.innogrid.aims.scheduler.model;

/**
 * Nova 인벤토리 모의 배치의 결과 상태.
 *
 * <p>모의 배치를 돌렸는지({@link #OK}), 돌리지 않았다면 왜인지를 나눈다. 인벤토리를 못 읽은 경우와 읽었지만 놓을 노드나
 * VM 이 없는 경우를 다른 값으로 둬서 「연동 실패」와 「대상 0」을 가른다.</p>
 */
public enum NovaDryRunStatus {
    /** 모의 배치를 돌렸다(못 놓은 VM 이 있어도 OK — 건수와 사유는 응답에 있다) */
    OK("Nova 인벤토리로 모의 배치를 했습니다(자원 가용성만 반영 · 전력 · 온도는 계측 없음)"),
    /** 기능 플래그가 꺼져 있어 Nova 를 부르지 않았다 */
    DISABLED("모의 배치가 꺼져 있습니다(aims.scheduler.inventory.simulation-enabled=false)"),
    /** 하이퍼바이저 목록이나 VM 목록을 읽지 못했다 — inventoryStatus · unavailable 에 이유가 있다 */
    INVENTORY_FAILED("Nova 인벤토리를 읽지 못해 모의 배치를 하지 않았습니다"),
    /** 하이퍼바이저는 읽었지만 쓸 수 있는 노드가 없다 */
    NO_NODES("쓸 수 있는 하이퍼바이저가 없어 모의 배치를 하지 않았습니다"),
    /** VM 목록은 읽었지만 다시 놓을 VM 이 없다 */
    NO_VMS("다시 놓을 VM 이 없어 모의 배치를 하지 않았습니다");

    private final String message;

    NovaDryRunStatus(String message) {
        this.message = message;
    }

    /** 상태마다 고정된 안내 문구. */
    public String message() {
        return message;
    }
}
