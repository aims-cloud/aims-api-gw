package com.innogrid.aims.scheduler.dto;

import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.innogrid.aims.scheduler.model.ExporterInstanceCandidates;
import com.innogrid.aims.scheduler.model.ExporterInstanceCandidates.Assignment;
import com.innogrid.aims.scheduler.model.ExporterInstanceCandidates.Candidate;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static org.junit.jupiter.api.Assertions.*;

/** 후보 한 줄의 응답 형태 — 7필드, 배정은 열거형 이름 그대로, 붙는 노드가 없으면 null로 직렬화된다. */
class ExporterInstanceCandidatesResponseTest {

    static final Set<String> CANDIDATE_FIELDS = new TreeSet<>(List.of("instance", "host", "gpuCount", "assignedNodeId", "assignedBy",
            "claimedByNodeIds", "ledgerNodeIds"));

    @DisplayName("후보 한 줄은 7필드이고 여섯 배정값이 이름 그대로 나가며 붙는 노드가 없으면 null 이다")
    @Test
    void candidate_seven_fields_and_assignment_names() {
        ExporterInstanceCandidates model = new ExporterInstanceCandidates(
                List.of(new Candidate("c:9400", "c", 1, null, Assignment.AMBIGUOUS, List.of("c:9100", "d:9100"), List.of("d:9100")),
                        new Candidate("z:9400", "z", 8, null, Assignment.UNASSIGNED, List.of(), List.of()),
                        new Candidate("u:9400", "u", 2, null, Assignment.UNKNOWN, List.of(), List.of("u:9100"))),
                List.of(new Candidate("ipmi:9290", "ipmi", null, "n1:9100", Assignment.LEDGER, List.of("n1:9100"), List.of("n1:9100")),
                        new Candidate("h:9290", "h", null, "h:9100", Assignment.HOSTNAME, List.of("h:9100"), List.of()),
                        new Candidate("s:9100", "s", null, "s:9100", Assignment.SAME_AS_NODE, List.of("s:9100"), List.of())),
                List.of("n1:9100"), List.of(), List.of(), List.of(ExporterInstanceCandidates.QUERY_NODES));

        JsonNode json = new ObjectMapper().valueToTree(ExporterInstanceCandidatesResponse.from(model));

        assertEquals(new TreeSet<>(List.of("dcgm", "ipmi", "nodeInstances", "ledgerOnlyDcgm", "ledgerOnlyIpmi", "unavailable")),
                new TreeSet<>(json.propertyNames()));
        for (JsonNode c : json.get("dcgm")) assertEquals(CANDIDATE_FIELDS, new TreeSet<>(c.propertyNames()), c.toString());
        for (JsonNode c : json.get("ipmi")) assertEquals(CANDIDATE_FIELDS, new TreeSet<>(c.propertyNames()), c.toString());

        JsonNode ambiguous = json.get("dcgm").get(0);
        assertEquals("AMBIGUOUS", ambiguous.get("assignedBy").asString());
        assertTrue(ambiguous.get("assignedNodeId").isNull());
        assertEquals(2, ambiguous.get("claimedByNodeIds").size());
        assertEquals("UNASSIGNED", json.get("dcgm").get(1).get("assignedBy").asString());
        assertEquals("UNKNOWN", json.get("dcgm").get(2).get("assignedBy").asString());
        assertEquals("LEDGER", json.get("ipmi").get(0).get("assignedBy").asString());
        assertTrue(json.get("ipmi").get(0).get("gpuCount").isNull(), "IPMI 는 카드 수가 없다");
        assertEquals("HOSTNAME", json.get("ipmi").get(1).get("assignedBy").asString());
        assertEquals("SAME_AS_NODE", json.get("ipmi").get(2).get("assignedBy").asString());
        assertEquals("nodes", json.get("unavailable").get(0).asString());
    }
}
