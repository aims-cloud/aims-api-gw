package com.innogrid.aims.scheduler.model;

/**
 * Nova 인벤토리 조회 결과의 상태.
 *
 * <p>실제로 비어 있는 인벤토리({@link #EMPTY})와 조회 실패(나머지 실패 값)를 나눈다. 실패 값은 하이퍼바이저 목록을
 * 읽지 못한 이유이며, 그때 노드 · 서버 목록은 비고 건수는 null이다.</p>
 */
public enum InventoryStatus {
    /** 하이퍼바이저를 1대 이상 읽음 */
    OK("Nova 인벤토리를 읽었습니다"),
    /** 연동은 정상이고 하이퍼바이저가 0대 */
    EMPTY("Nova 가 하이퍼바이저 0대를 돌려줬습니다(연동은 정상)"),
    /** OpenStack 접속 정보가 설정되지 않음 */
    NOT_CONFIGURED("OpenStack 연동 설정이 없습니다(OPENSTACK_* 환경변수)"),
    /** Keystone · Nova가 인증 실패(401)로 응답 */
    AUTH_FAILED("인증에 실패했습니다(401)"),
    /** 권한 부족(403) — 하이퍼바이저 목록은 관리자 권한이 필요하다 */
    FORBIDDEN("권한이 없습니다(403) — 하이퍼바이저 목록은 관리자 권한이 필요합니다"),
    /** 연결 자체가 되지 않음(주소 · 포트 · 네트워크) */
    CONNECTION_FAILED("OpenStack 에 연결하지 못했습니다"),
    /** 시간 상한 안에 끝나지 않았거나 앞선 조회가 아직 끝나지 않음 */
    TIMEOUT("시간 상한 안에 응답이 오지 않았거나 앞선 조회가 아직 끝나지 않았습니다"),
    /** 상류가 오류(5xx 등)로 응답하거나 토큰 응답이 비정상 */
    UPSTREAM_ERROR("OpenStack 이 오류로 응답했습니다"),
    /** 응답을 읽을 수 없거나 목록 키가 없음 */
    MALFORMED("응답 형식이 예상과 다릅니다");

    private final String message;

    InventoryStatus(String message) {
        this.message = message;
    }

    /** 상태마다 고정된 안내 문구. 예외 원문과 주소는 담지 않는다. */
    public String message() {
        return message;
    }
}
