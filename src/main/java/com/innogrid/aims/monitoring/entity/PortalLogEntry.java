package com.innogrid.aims.monitoring.entity;

import java.time.OffsetDateTime;

import com.innogrid.aims.monitoring.dto.log.PortalLogResult;
import com.innogrid.aims.monitoring.dto.log.PortalLogType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

/**
 * 포털 로그 — 포털에서 일어난 일(사용자 조작·인증·인프라 작업·알람 이벤트·시스템 동작)을 한 테이블에 모은 원장
 * (docs/08 포털 로그 설계 §3.1). 관측 > 로그 관리 화면이 조회한다.
 *
 * <ul>
 *   <li>요청 로그(AUTH/AUDIT/ACTION/ACCESS)는 {@code @PortalLog} Aspect 가, 이벤트 로그(ALARM/SYSTEM)는 서비스가 직접 만든다.</li>
 *   <li>사용자·표시명은 기록 시점 스냅샷이다. 시스템 이벤트는 사용자·IP 가 null 이다.</li>
 *   <li>{@code refType/refId} 는 연관 원장(알람 이력, 작업)을 가리키는 약한 참조다 — FK 를 걸지 않는다.</li>
 *   <li>문자열 컬럼은 길이를 넘는 값을 잘라 저장한다(로그가 본 요청을 실패시키지 않도록).</li>
 * </ul>
 */
@Entity
@Table(name = "portal_logs", indexes = {
        @Index(name = "idx_portal_logs_started_at", columnList = "started_at"),
        @Index(name = "idx_portal_logs_type_started_at", columnList = "type, started_at"),
        @Index(name = "idx_portal_logs_user_started_at", columnList = "user_id, started_at"),
        @Index(name = "idx_portal_logs_ref", columnList = "ref_type, ref_id")
})
public class PortalLogEntry {

    static final int USER_ID_MAX = 64;
    static final int USER_NAME_MAX = 100;
    static final int CLIENT_IP_MAX = 45;
    static final int SCREEN_MAX = 50;
    static final int ACTION_MAX = 30;
    static final int CONTENT_MAX = 500;
    static final int DESCRIPTION_MAX = 1000;
    static final int REF_TYPE_MAX = 30;
    static final int REF_ID_MAX = 100;
    static final int REQUEST_ID_MAX = 64;
    static final int HTTP_METHOD_MAX = 8;
    static final int REQUEST_URI_MAX = 300;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, length = 16)
    private PortalLogType type;

    /** 요청 진입 또는 이벤트 시각 — 화면의 "생성 일시" */
    @Column(name = "started_at", nullable = false)
    private OffsetDateTime startedAt;

    /** 응답 반환 또는 작업 완료 시각 — IN_PROGRESS 면 null */
    @Column(name = "completed_at")
    private OffsetDateTime completedAt;

    @Column(name = "user_id", length = USER_ID_MAX)
    private String userId;

    @Column(name = "user_name", length = USER_NAME_MAX)
    private String userName;

    @Column(name = "client_ip", length = CLIENT_IP_MAX)
    private String clientIp;

    @Column(name = "screen", length = SCREEN_MAX)
    private String screen;

    @Column(name = "action", length = ACTION_MAX)
    private String action;

    @Column(name = "content", nullable = false, length = CONTENT_MAX)
    private String content;

    @Enumerated(EnumType.STRING)
    @Column(name = "result", nullable = false, length = 16)
    private PortalLogResult result;

    @Column(name = "description", length = DESCRIPTION_MAX)
    private String description;

    @Column(name = "ref_type", length = REF_TYPE_MAX)
    private String refType;

    @Column(name = "ref_id", length = REF_ID_MAX)
    private String refId;

    @Column(name = "request_id", length = REQUEST_ID_MAX)
    private String requestId;

    @Column(name = "http_method", length = HTTP_METHOD_MAX)
    private String httpMethod;

    @Column(name = "request_uri", length = REQUEST_URI_MAX)
    private String requestUri;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) {
            createdAt = OffsetDateTime.now();
        }
        if (startedAt == null) {
            startedAt = createdAt;
        }
    }

    protected PortalLogEntry() {}

    private PortalLogEntry(Builder b) {
        this.type = b.type;
        this.startedAt = b.startedAt;
        this.completedAt = b.completedAt;
        this.userId = truncate(b.userId, USER_ID_MAX);
        this.userName = truncate(b.userName, USER_NAME_MAX);
        this.clientIp = truncate(b.clientIp, CLIENT_IP_MAX);
        this.screen = truncate(b.screen, SCREEN_MAX);
        this.action = truncate(b.action, ACTION_MAX);
        this.content = truncate(b.content == null || b.content.isBlank() ? "-" : b.content, CONTENT_MAX);
        this.result = b.result;
        this.description = truncate(b.description, DESCRIPTION_MAX);
        this.refType = truncate(b.refType, REF_TYPE_MAX);
        this.refId = truncate(b.refId, REF_ID_MAX);
        this.requestId = truncate(b.requestId, REQUEST_ID_MAX);
        this.httpMethod = truncate(b.httpMethod, HTTP_METHOD_MAX);
        this.requestUri = truncate(b.requestUri, REQUEST_URI_MAX);
    }

    public static Builder builder(PortalLogType type) {
        return new Builder(type);
    }

    /** 진행중(IN_PROGRESS) 행을 최종 결과로 갱신한다 — ACTION 작업 완료 콜백용 */
    public void complete(PortalLogResult finalResult, String description, OffsetDateTime completedAt) {
        this.result = finalResult;
        this.description = truncate(description, DESCRIPTION_MAX);
        this.completedAt = completedAt;
    }

    static String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max);
    }

    public Long getId() { return id; }
    public PortalLogType getType() { return type; }
    public OffsetDateTime getStartedAt() { return startedAt; }
    public OffsetDateTime getCompletedAt() { return completedAt; }
    public String getUserId() { return userId; }
    public String getUserName() { return userName; }
    public String getClientIp() { return clientIp; }
    public String getScreen() { return screen; }
    public String getAction() { return action; }
    public String getContent() { return content; }
    public PortalLogResult getResult() { return result; }
    public String getDescription() { return description; }
    public String getRefType() { return refType; }
    public String getRefId() { return refId; }
    public String getRequestId() { return requestId; }
    public String getHttpMethod() { return httpMethod; }
    public String getRequestUri() { return requestUri; }
    public OffsetDateTime getCreatedAt() { return createdAt; }

    public static final class Builder {
        private final PortalLogType type;
        private OffsetDateTime startedAt = OffsetDateTime.now();
        private OffsetDateTime completedAt;
        private String userId;
        private String userName;
        private String clientIp;
        private String screen;
        private String action;
        private String content;
        private PortalLogResult result = PortalLogResult.SUCCESS;
        private String description;
        private String refType;
        private String refId;
        private String requestId;
        private String httpMethod;
        private String requestUri;

        private Builder(PortalLogType type) {
            this.type = type;
        }

        public Builder startedAt(OffsetDateTime v) { this.startedAt = v; return this; }
        public Builder completedAt(OffsetDateTime v) { this.completedAt = v; return this; }
        public Builder user(String userId, String userName) { this.userId = userId; this.userName = userName; return this; }
        public Builder clientIp(String v) { this.clientIp = v; return this; }
        public Builder screen(String v) { this.screen = v; return this; }
        public Builder action(String v) { this.action = v; return this; }
        public Builder content(String v) { this.content = v; return this; }
        public Builder result(PortalLogResult v) { this.result = v; return this; }
        public Builder description(String v) { this.description = v; return this; }
        public Builder ref(String refType, String refId) { this.refType = refType; this.refId = refId; return this; }
        public Builder requestId(String v) { this.requestId = v; return this; }
        public Builder request(String httpMethod, String requestUri) { this.httpMethod = httpMethod; this.requestUri = requestUri; return this; }

        public PortalLogEntry build() {
            return new PortalLogEntry(this);
        }
    }
}
