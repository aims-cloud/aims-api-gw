package com.innogrid.aims.scheduler.service;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;

import com.innogrid.aims.common.exception.BusinessException;
import com.innogrid.aims.scheduler.dto.SchedulingPolicyRequest;
import com.innogrid.aims.scheduler.engine.EngineRegistry;
import com.innogrid.aims.scheduler.engine.WeightedScoreScheduler;
import com.innogrid.aims.scheduler.entity.SchedulingPolicy;
import com.innogrid.aims.scheduler.repository.ScheduleRepository;
import com.innogrid.aims.scheduler.repository.SchedulingPolicyRepository;

import tools.jackson.databind.node.JsonNodeFactory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 정책 수정 전 모의 배치의 고친 사본 — 404 가 본문 검사보다 먼저, 본문 없음 400, 값 검사는 PUT 과 같은 문구, 사본만 고치고 저장된 정책 ·
 * 저장소는 그대로, 이름 · 설명은 보지 않고 종류 · 활성은 형식만 검사.
 */
class SchedulingPolicyPreviewEditTest {

    private final SchedulingPolicyRepository repository = mock(SchedulingPolicyRepository.class);
    private final SchedulingPolicyService service = new SchedulingPolicyService(repository,
            mock(ScheduleRepository.class), new EngineRegistry(List.of(new WeightedScoreScheduler())));

    @DisplayName("없는 정책은 본문이 없어도 404 이고, 있는 정책에 본문이 없으면 400 이다")
    @Test
    void not_found_comes_before_missing_body() {
        when(repository.findById(9L)).thenReturn(Optional.empty());
        BusinessException missing = assertThrows(BusinessException.class, () -> service.previewEdit(9L, false, null));
        assertEquals(HttpStatus.NOT_FOUND, missing.getStatus());

        when(repository.findById(1L)).thenReturn(Optional.of(saved()));
        BusinessException noBody = assertThrows(BusinessException.class, () -> service.previewEdit(1L, false, null));
        assertEquals(HttpStatus.BAD_REQUEST, noBody.getStatus());
        assertEquals("요청 본문이 필요합니다", noBody.getMessage());
    }

    @DisplayName("틀린 값은 PUT 과 같은 문구의 400 이다")
    @Test
    void invalid_values_use_the_put_message() {
        when(repository.findById(1L)).thenReturn(Optional.of(saved()));
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        SchedulingPolicyRequest negative = changes(-1.0, null, null, null);

        String preview = assertThrows(BusinessException.class, () -> service.previewEdit(1L, true, negative))
                .getMessage();
        String put = assertThrows(BusinessException.class, () -> service.update(1L, negative)).getMessage();

        assertEquals(put, preview);
        assertTrue(preview.contains("cpuWeight"), preview);

        SchedulingPolicyRequest badType = changes(null, null, "NOPE", null);
        assertEquals(assertThrows(BusinessException.class, () -> service.update(1L, badType)).getMessage(),
                assertThrows(BusinessException.class, () -> service.previewEdit(1L, true, badType)).getMessage(),
                "리소스 종류 형식 검사도 PUT 과 같은 문구");
    }

    @DisplayName("사본만 고치고 저장된 정책 · 저장소는 그대로 두며, 이름 · 설명은 보지 않고 종류 · 활성은 저장된 값을 둔다")
    @Test
    void edits_only_the_copy() {
        SchedulingPolicy saved = saved();
        when(repository.findById(1L)).thenReturn(Optional.of(saved));
        SchedulingPolicyRequest request = new SchedulingPolicyRequest("x".repeat(500), "y".repeat(5000),
                JsonNodeFactory.instance.stringNode("GPU"), null, null,
                null, null, null, null, null,
                50.0, null, null, null,
                null, null, true, false);

        SchedulingPolicyService.PolicyEdit edit = service.previewEdit(1L, true, request);

        assertNotSame(saved, edit.edited());
        assertEquals(saved, edit.saved());
        assertEquals(50.0, edit.edited().getMaxCpuUsagePercent());
        assertTrue(edit.edited().isAffinityEnabled());
        assertEquals(80.0, saved.getMaxCpuUsagePercent(), "저장된 정책 값 그대로");
        assertEquals("p", edit.edited().getName(), "이름은 보지 않는다(길이 400 도 없음)");
        assertNull(edit.edited().getResourceType(), "종류는 형식만 검사하고 저장된 값(공통)을 둔다");
        assertTrue(edit.edited().isActive(), "활성 여부도 저장된 값");
        assertNull(edit.edited().getId(), "사본은 id 가 없다");
        verify(repository, never()).save(any());
        verify(repository, never()).saveAll(anyList());

        SchedulingPolicyService.PolicyEdit none = service.previewEdit(1L, true, null);
        assertEquals(80.0, none.edited().getMaxCpuUsagePercent(), "수정 값이 없으면 저장된 값 그대로의 사본");
        assertNotSame(saved, none.edited());
    }

    private static SchedulingPolicy saved() {
        SchedulingPolicy p = new SchedulingPolicy("p", "d");
        p.setActive(true);
        ReflectionTestUtils.setField(p, "id", 1L);
        return p;
    }

    /** cpuWeight · maxCpuUsagePercent · resourceType · affinityEnabled 만 채운 부분 수정. */
    private static SchedulingPolicyRequest changes(Double cpuWeight, Double maxCpu, String resourceType,
                                                   Boolean affinity) {
        return new SchedulingPolicyRequest(null, null,
                resourceType == null ? null : JsonNodeFactory.instance.stringNode(resourceType), null, null,
                cpuWeight, null, null, null, null,
                maxCpu, null, null, null,
                null, null, affinity, null);
    }
}
