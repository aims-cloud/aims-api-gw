package com.innogrid.aims.monitoring.service.resourceexplorer.tree;

import com.innogrid.aims.monitoring.dto.resourceexplorer.ResourceStatus;

/**
 * Tumblebug 상태값 → 집계 버킷 매핑 (docs/06 §6.1).
 *
 * 트리 노드와 상세 화면에는 Tumblebug 상태값을 원문으로 노출하고,
 * 역할·Namespace·Infra·NodeGroup 패널의 상태 개수 카드(running/pending/stop/error)에서만 이 버킷을 사용한다.
 *
 * Node(VM) 상태는 {@code Running} 처럼 단어 하나지만, Infra 집계 상태는
 * {@code "Running:1 (R:1/1)"}, {@code "Running-(3/3)"}, {@code "Partial-Failed:1 (R:1/2)"} 처럼
 * 구분자({@code :} {@code -} {@code (} 공백) 뒤에 개수 접미가 붙고 {@code Partial-} 접두가 올 수 있다.
 * 접두·접미를 떼고 상태 단어만 비교하며, {@code Partial-} 은 Node 일부만 그 상태라는 뜻이라
 * {@code Partial-Running} 은 PENDING(혼재), {@code Partial-Failed} 등은 뒤 단어의 버킷을 따른다.
 */
public final class TumblebugStatusMapper {

    private static final String PARTIAL_PREFIX = "Partial-";

    private TumblebugStatusMapper() {}

    public static ResourceStatus bucket(String tumblebugStatus) {
        if (tumblebugStatus == null || tumblebugStatus.isBlank()) {
            return ResourceStatus.ERROR;                                // Undefined 취급
        }
        String head = tumblebugStatus.trim();
        boolean partial = head.regionMatches(true, 0, PARTIAL_PREFIX, 0, PARTIAL_PREFIX.length());
        if (partial) {
            head = head.substring(PARTIAL_PREFIX.length());
        }
        head = head.split("[-:(\\s]", 2)[0].trim();

        ResourceStatus bucket = switch (head) {
            case "Running" -> ResourceStatus.RUNNING;
            case "Creating", "Registering", "Reconciling", "Suspending", "Resuming", "Rebooting", "Terminating",
                 "Preparing", "Prepared" -> ResourceStatus.PENDING;
            case "Suspended", "Terminated" -> ResourceStatus.STOP;
            default -> ResourceStatus.ERROR;                            // Failed, Undefined, 그 외
        };
        // 일부 Node 만 Running 인 Infra 는 전체가 정상이라 볼 수 없어 PENDING 으로 둔다
        return partial && bucket == ResourceStatus.RUNNING ? ResourceStatus.PENDING : bucket;
    }
}
