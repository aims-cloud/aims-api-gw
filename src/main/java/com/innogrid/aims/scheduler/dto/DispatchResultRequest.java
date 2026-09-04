package com.innogrid.aims.scheduler.dto;

/**
 * 실행 계층이 보고하는 결과. {@code status} 는 RUNNING | COMPLETED | FAILED (대소문자 무시).
 * {@code externalRef} 는 실행 계층 쪽 식별자, {@code message} 는 사유·비고(선택).
 */
public record DispatchResultRequest(String status, String externalRef, String message) {}
