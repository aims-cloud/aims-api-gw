package com.innogrid.aims.monitoring.service.resourceexplorer;

import java.util.Map;

/**
 * Loki 로그 조회 대상 — 스트림 선택자에 넣을 라벨 묶음(docs/11 §3).
 *
 * 물리 노드는 {@code instance·mdc_id}({@link ResourceScopeService.ScopedHost}), Tumblebug VM 은 {@code tb_ns·tb_infra·tb_node}
 * 로 고른다. 수집기(Alloy)가 external_labels 로 같은 라벨을 붙여 보낸다. 값이 하나라도 비어 있으면 라벨 매핑이 없는 대상이다.
 */
public interface LogScope {

    /** 선택자 라벨 — 순서를 유지한다(LogQL 문자열과 테스트 기대값이 안정적이도록). 값이 null 일 수 있다 */
    Map<String, String> logLabels();

    /** 라벨이 하나 이상이고 모든 값이 비어 있지 않은가 */
    default boolean logConfigured() {
        Map<String, String> labels = logLabels();
        return !labels.isEmpty() && labels.values().stream().allMatch(value -> value != null && !value.isBlank());
    }
}
