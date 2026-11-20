package com.innogrid.aims.monitoring.dto.log;

/**
 * 포털 로그 결과 — 화면의 성공/실패/진행중 Badge.
 * IN_PROGRESS 는 ACTION 타입(비동기 작업)에서만 쓰이며 완료 시 SUCCESS/FAILURE 로 갱신된다.
 */
public enum PortalLogResult {
    SUCCESS,
    FAILURE,
    IN_PROGRESS
}
