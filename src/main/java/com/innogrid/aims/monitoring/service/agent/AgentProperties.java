package com.innogrid.aims.monitoring.service.agent;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** 수집 에이전트 목록 전송 설정 (prefix: aims.agent). docs/11 §4.4·§5.3 */
@Component
@ConfigurationProperties(prefix = "aims.agent")
public class AgentProperties {

    /**
     * Alloy 정적 설정(config.alloy)이 이미 읽는 경로. 원장에 같은 경로를 등록해도 목록에 넣지 않아 중복 적재를 막는다.
     * 항목은 정확한 경로 또는 {@code *} glob(디렉터리 구분자는 넘지 않음).
     */
    private List<String> staticLogPaths = List.of(
            "/var/log/syslog", "/var/log/auth.log", "/var/log/kern.log", "/var/log/aims/*.log");

    /** 재적용 스케줄러 주기(초). 원하는 목록과 노드의 실제 파일이 다르면 다시 쓴다. */
    private long reconcileIntervalSeconds = 60;

    public List<String> getStaticLogPaths() { return staticLogPaths; }
    public void setStaticLogPaths(List<String> staticLogPaths) { this.staticLogPaths = staticLogPaths; }

    public long getReconcileIntervalSeconds() { return reconcileIntervalSeconds; }
    public void setReconcileIntervalSeconds(long reconcileIntervalSeconds) { this.reconcileIntervalSeconds = reconcileIntervalSeconds; }
}
