package com.innogrid.aims.scheduler.model;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 결정 결과의 적용 정책 이름 — 엔진이 만든 6인자 결과는 비어 있고, 배치 서비스가 채운 사본만 값을 가진다. */
class SchedulingResultTest {

    @DisplayName("6인자 생성자와 failure(reason) 은 정책 이름이 없다")
    @Test
    void six_arg_and_plain_failure_have_no_policy() {
        SchedulingResult r = new SchedulingResult(true, "mdc-01", "n1", 0.7, "ok", List.of());
        assertNull(r.policyName());
        assertNull(SchedulingResult.failure("x").policyName());
    }

    @DisplayName("withPolicyName 은 정책 이름만 채우고 나머지 값은 그대로 둔다")
    @Test
    void with_policy_name_keeps_other_components() {
        SchedulingResult.NodeScore score = new SchedulingResult.NodeScore("mdc-01", "n1", 1, 1, 1, 1, 1, 5, true, null);
        SchedulingResult r = new SchedulingResult(true, "mdc-01", "n1", 0.7, "ok", List.of(score)).withPolicyName("vm-only");
        assertEquals("vm-only", r.policyName());
        assertTrue(r.success());
        assertEquals("mdc-01", r.selectedMdcId());
        assertEquals("n1", r.selectedNodeId());
        assertEquals(0.7, r.finalScore());
        assertEquals("ok", r.reason());
        assertSame(score, r.candidateScores().get(0));
    }

    @DisplayName("failure(reason, policyName) 은 실패 결과에도 적용 정책을 남긴다")
    @Test
    void failure_with_policy_keeps_name() {
        SchedulingResult r = SchedulingResult.failure("후보 없음", "default");
        assertFalse(r.success());
        assertEquals("후보 없음", r.reason());
        assertEquals("default", r.policyName());
        assertTrue(r.candidateScores().isEmpty());
    }
}
