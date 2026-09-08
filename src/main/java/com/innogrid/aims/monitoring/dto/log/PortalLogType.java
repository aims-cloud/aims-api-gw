package com.innogrid.aims.monitoring.dto.log;

/**
 * 포털 로그 타입 (docs/08 포털 로그 설계 §2).
 * 한 테이블(portal_logs)에 모아 두고 이 값으로 구분한다.
 */
public enum PortalLogType {
    /** 인증·세션·권한 — 로그인 성공/실패, 로그아웃, 토큰 갱신, 비밀번호 변경, 권한 거부 */
    AUTH,
    /** 포털 데이터·설정 변경(동기 CRUD) — 즉시 완료 */
    AUDIT,
    /** 인프라에 실제 영향을 주는 작업 — 진행중 상태가 있고 완료가 나중에 갱신된다 */
    ACTION,
    /** 알람 발생·해소·확인 이벤트 — 원장은 alarm_history, 여기엔 요약 행만 */
    ALARM,
    /** 백엔드 내부 동작·외부 연동 상태 — 상태 변화가 있을 때만 기록 */
    SYSTEM,
    /** 조회·열람 — 기본 비활성, 명시한 곳(개인정보·내보내기)만 */
    ACCESS
}
