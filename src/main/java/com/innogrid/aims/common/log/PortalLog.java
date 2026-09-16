package com.innogrid.aims.common.log;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import com.innogrid.aims.monitoring.dto.log.PortalLogType;

/**
 * 컨트롤러 메서드에 붙여 포털 로그를 자동 기록한다 (docs/08 포털 로그 설계 §4·§5).
 *
 * Aspect 가 사용자·IP·요청 ID·시각을 채우고, 정상 반환이면 SUCCESS, 예외면 FAILURE 로 남긴다.
 * {@code content}·{@code refId}·{@code userId} 는 SpEL 식이며 메서드 인자(이름 또는 {@code #p0}), {@code #result}(성공 시),
 * {@code #userId}, {@code #userName} 을 쓸 수 있다. 내용은 실패 시에도 만들어져야 하므로 요청 인자 기준으로 쓴다.
 *
 * <pre>
 * &#64;PortalLog(screen = "네트워크 구역", action = "생성", content = "'네트워크 구역 생성: ' + #request.name")
 * &#64;PostMapping
 * public ApiResponse&lt;NetworkZoneResponse&gt; create(@RequestBody NetworkZoneRequest request) { ... }
 *
 * &#64;PortalLog(type = PortalLogType.AUTH, screen = "로그인", action = "로그인",
 *            content = "'로그인: ' + #request.email", userId = "#request.email")
 * &#64;PostMapping("/login")
 * public ApiResponse&lt;?&gt; login(@RequestBody LoginRequest request) { ... }
 * </pre>
 *
 * 요청이 없는 곳(스케줄러·알람 엔진)은 애너테이션 대신 {@code PortalLogService.record(...)} 를 직접 호출한다.
 */
@Documented
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface PortalLog {

    /** 로그 타입 — 기본 AUDIT. 인증은 AUTH, 인프라 작업은 ACTION, 열람 기록은 ACCESS */
    PortalLogType type() default PortalLogType.AUDIT;

    /** 화면 표시명. 예: "네트워크 구역" */
    String screen();

    /** 작업. 예: "생성", "수정", "삭제", "로그인" */
    String action();

    /** 내용 SpEL 식. 예: {@code "'네트워크 구역 생성: ' + #request.name"} */
    String content();

    /** true 면 정상 반환 시 IN_PROGRESS 로 남기고, 완료는 {@code PortalLogService.complete} 로 나중에 갱신한다 (ACTION 용) */
    boolean async() default false;

    /** 연관 원장 종류. 예: "INFRA_JOB" */
    String refType() default "";

    /** 연관 원장 키 SpEL 식 (성공 시 평가). 예: {@code "#result.data.jobId"} */
    String refId() default "";

    /**
     * 사용자 ID SpEL 식 (요청 인자 기준). 인증 주체가 없는 요청(로그인·가입)에서 누가 시도했는지 남길 때 쓴다.
     * 비어 있으면 SecurityContext 의 인증 사용자를 쓴다. 예: {@code "#request.email"}
     */
    String userId() default "";
}
