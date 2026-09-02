package com.innogrid.aims.scheduler.dto;

/**
 * 워크로드 실행 상태 전이 요청 — {@code PATCH /workloads/{id}/status}.
 * 상태는 문자열로 받아 기존 파서가 대소문자를 무시해 해석한다.
 */
public record StatusChangeRequest(String status) {}
