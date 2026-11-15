package com.innogrid.aims.scheduler.model;

/**
 * 엔진을 고른 단계.
 *
 * <p>실행 미리보기 · 모의 실행 응답의 {@code engineSource} 와 서비스의 엔진 선택 결과가 함께 쓴다.</p>
 */
public enum EngineSource {
    /** 스케줄이 지정한 엔진 */
    SCHEDULE,
    /** 정책의 기본 엔진(등록된 엔진일 때만) */
    POLICY,
    /** 서버 기본 엔진 */
    DEFAULT
}
