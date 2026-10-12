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

    /**
     * 배포 패키지(deploy/alloy-node, VM 은 classpath agent/vm/config.alloy)의 Alloy 정적 설정이 읽는 경로 — PULL 노드(세부1 노드, TB VM)용.
     * k8s-cp(LOCAL)의 정적 설정과 달리 Debian·RHEL 경로를 함께 읽고 AIMS 앱 로그는 없다.
     */
    private List<String> remoteStaticLogPaths = List.of(
            "/var/log/syslog", "/var/log/messages", "/var/log/auth.log", "/var/log/secure", "/var/log/kern.log");

    /** 재적용 스케줄러 주기(초). 원하는 목록과 노드의 실제 파일이 다르면 다시 쓴다. */
    private long reconcileIntervalSeconds = 60;

    public List<String> getStaticLogPaths() { return staticLogPaths; }
    public void setStaticLogPaths(List<String> staticLogPaths) { this.staticLogPaths = staticLogPaths; }

    public List<String> getRemoteStaticLogPaths() { return remoteStaticLogPaths; }
    public void setRemoteStaticLogPaths(List<String> remoteStaticLogPaths) { this.remoteStaticLogPaths = remoteStaticLogPaths; }

    /** Tumblebug VM 수집 에이전트 설치(docs/11 §5.5) */
    private VmInstall vmInstall = new VmInstall();

    public long getReconcileIntervalSeconds() { return reconcileIntervalSeconds; }
    public void setReconcileIntervalSeconds(long reconcileIntervalSeconds) { this.reconcileIntervalSeconds = reconcileIntervalSeconds; }

    public VmInstall getVmInstall() { return vmInstall; }
    public void setVmInstall(VmInstall vmInstall) { this.vmInstall = vmInstall; }

    /**
     * VM 설치 설정 (prefix: aims.agent.vm-install). 두 URL 은 <b>VM 에서 보이는</b> 주소여야 한다 — VM 의 Alloy 가 직접 부른다.
     * 둘 중 하나라도 비어 있으면 설치 API 는 503 이다.
     */
    public static class VmInstall {
        /** Loki push 주소. 예) http://&lt;수집서버&gt;:3100/loki/api/v1/push */
        private String lokiPushUrl;
        /** 백엔드 수집 대상 목록 API. 예) http://&lt;백엔드&gt;:8080/api/v1/agent/targets */
        private String targetsUrl;
        /** Grafana Alloy 릴리스 버전(k8s-cp 와 같게) */
        private String alloyVersion = "1.20.0";
        /** node_exporter 릴리스 버전(docs/07 §3.1 과 같게) */
        private String nodeExporterVersion = "1.9.1";
        /** Tumblebug 원격 명령의 SSH 사용자. 비우면 Tumblebug 이 VM 에 기록한 사용자 */
        private String userName;
        /** 원격 명령 제한 시간(분, Tumblebug 허용 1~120). 다운로드가 포함되어 기본 15분 */
        private int timeoutMinutes = 15;

        public String getLokiPushUrl() { return lokiPushUrl; }
        public void setLokiPushUrl(String lokiPushUrl) { this.lokiPushUrl = lokiPushUrl; }
        public String getTargetsUrl() { return targetsUrl; }
        public void setTargetsUrl(String targetsUrl) { this.targetsUrl = targetsUrl; }
        public String getAlloyVersion() { return alloyVersion; }
        public void setAlloyVersion(String alloyVersion) { this.alloyVersion = alloyVersion; }
        public String getNodeExporterVersion() { return nodeExporterVersion; }
        public void setNodeExporterVersion(String nodeExporterVersion) { this.nodeExporterVersion = nodeExporterVersion; }
        public String getUserName() { return userName; }
        public void setUserName(String userName) { this.userName = userName; }
        public int getTimeoutMinutes() { return timeoutMinutes; }
        public void setTimeoutMinutes(int timeoutMinutes) { this.timeoutMinutes = timeoutMinutes; }
    }
}
