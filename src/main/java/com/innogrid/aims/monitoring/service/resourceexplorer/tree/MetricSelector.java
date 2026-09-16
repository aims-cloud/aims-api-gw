package com.innogrid.aims.monitoring.service.resourceexplorer.tree;

/**
 * Thanos 시계열을 자원 하나로 좁히는 PromQL 라벨 매처 (docs/06 §9.6).
 *
 * <ul>
 *   <li>물리 호스트(1세부): {@code instance="address:port"} — monitoring_target 의 scrape 주소</li>
 *   <li>Tumblebug VM(2세부): {@code tb_ns="…",tb_infra="…",tb_node="…"} — exporter 등록 시 relabel 로 심는 라벨.
 *       IP 로 대조하지 않는다.</li>
 * </ul>
 * 같은 VM 의 node_exporter 와 dcgm_exporter 는 라벨이 같으므로 Tumblebug 선택자는 두 exporter 에 공통으로 쓴다.
 */
public record MetricSelector(String matchers) {

    /** monitoring_target scrape 주소(address:port)로 물리 호스트 exporter 하나를 가리킨다 */
    public static MetricSelector instance(String instance) {
        return new MetricSelector(label("instance", instance));
    }

    /** Tumblebug Namespace/Infra/Node ID 로 VM 하나를 가리킨다 */
    public static MetricSelector tumblebugNode(String nsId, String infraId, String nodeId) {
        return new MetricSelector(label("tb_ns", nsId) + "," + label("tb_infra", infraId) + "," + label("tb_node", nodeId));
    }

    /** 완전 일치 라벨 매처 한 개 — {@code name="value"}. 값의 역슬래시·큰따옴표는 이스케이프한다 */
    public static String label(String name, String value) {
        return name + "=\"" + escape(value) + "\"";
    }

    /** 이 선택자에 라벨 매처를 덧붙인다 (예: {@code mode="idle"}) */
    public String and(String extraMatchers) {
        return extraMatchers == null || extraMatchers.isBlank() ? matchers : extraMatchers + "," + matchers;
    }

    /** 중괄호로 감싼 완성형 — {@code {instance="…"}} */
    public String braces() {
        return "{" + matchers + "}";
    }

    /** 추가 매처를 포함해 중괄호로 감싼 완성형 — {@code {mode="idle",instance="…"}} */
    public String braces(String extraMatchers) {
        return "{" + and(extraMatchers) + "}";
    }

    private static String escape(String value) {
        return value == null ? "" : value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
