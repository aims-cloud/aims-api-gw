package com.innogrid.aims.scheduler.dto;

import java.util.List;

import com.innogrid.aims.scheduler.model.ExporterInstanceCandidates;

/**
 * 익스포터 instance 후보 응답 — 최상위 6필드, 후보 한 줄은 7필드. 최상위 계약은 {@code NodeExporterMappingApiTest} 가,
 * 후보 한 줄의 계약은 {@code SyntheticModeScenarioTest}(값이 있는 컨텍스트)와 {@code ExporterInstanceCandidatesResponseTest} 가 고정한다.
 *
 * <p>{@code unavailable} 이 비어 있는데 후보 목록이 비면 그 익스포터 시리즈가 저장소에 없는 것이다(배포 서버가 그렇다 —
 * DCGM·IPMI 타깃이 내려가 있다). {@code unavailable} 에 이름이 있으면 그 목록은 답을 못 받은 것이라 비었다고 읽으면 안 되고,
 * 못 받은 질의가 뒷받침하는 칸({@code nodes} → 배정 {@code UNKNOWN}, {@code dcgm}/{@code ipmi} → 그쪽 {@code ledgerOnly} 빈 목록)은
 * 서버가 비워 둔다.</p>
 */
public record ExporterInstanceCandidatesResponse(
        List<Candidate> dcgm,
        List<Candidate> ipmi,
        List<String> nodeInstances,
        List<String> ledgerOnlyDcgm,
        List<String> ledgerOnlyIpmi,
        List<String> unavailable
) {
    public record Candidate(
            String instance,
            String host,
            Integer gpuCount,
            String assignedNodeId,
            String assignedBy,
            List<String> claimedByNodeIds,
            List<String> ledgerNodeIds
    ) {
        public static Candidate from(ExporterInstanceCandidates.Candidate c) {
            return new Candidate(c.instance(), c.host(), c.gpuCount(), c.assignedNodeId(),
                    c.assignedBy() == null ? null : c.assignedBy().name(), c.claimedByNodeIds(), c.ledgerNodeIds());
        }
    }

    public static ExporterInstanceCandidatesResponse from(ExporterInstanceCandidates m) {
        return new ExporterInstanceCandidatesResponse(
                m.dcgm().stream().map(Candidate::from).toList(),
                m.ipmi().stream().map(Candidate::from).toList(),
                m.nodeInstances(), m.ledgerOnlyDcgm(), m.ledgerOnlyIpmi(), m.unavailable());
    }
}
