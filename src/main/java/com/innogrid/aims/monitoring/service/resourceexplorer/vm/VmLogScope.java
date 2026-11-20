package com.innogrid.aims.monitoring.service.resourceexplorer.vm;

import java.util.LinkedHashMap;
import java.util.Map;

import com.innogrid.aims.monitoring.service.resourceexplorer.LogScope;

/**
 * Tumblebug VM 로그 조회 대상 — {@code tb_ns·tb_infra·tb_node} 라벨(메트릭 선택자 {@code MetricSelector.tumblebugNode} 와 같은 키).
 * VM 의 Alloy 가 external_labels 로 이 세 라벨을 붙여 Loki 로 보낸다(deploy/alloy-vm).
 */
public record VmLogScope(String nsId, String infraId, String vmId) implements LogScope {

    @Override
    public Map<String, String> logLabels() {
        Map<String, String> labels = new LinkedHashMap<>();
        labels.put("tb_ns", nsId);
        labels.put("tb_infra", infraId);
        labels.put("tb_node", vmId);
        return labels;
    }
}
