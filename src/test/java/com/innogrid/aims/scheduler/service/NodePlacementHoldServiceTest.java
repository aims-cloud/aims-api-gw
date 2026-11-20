package com.innogrid.aims.scheduler.service;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import com.innogrid.aims.common.exception.BusinessException;
import com.innogrid.aims.scheduler.dto.NodePlacementHoldRequest;
import com.innogrid.aims.scheduler.entity.NodePlacementHold;
import com.innogrid.aims.scheduler.repository.NodePlacementHoldRepository;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 노드 배치 중지 원장의 저장 경합 — 중복 검사를 지나친 동시 등록이 유니크 제약에 걸려도 409 로 끝나는지.
 *
 * <p>전력 사양 원장 테스트와 같은 구성(가짜 빈 없음)이라 같은 스프링 컨텍스트를 쓴다.</p>
 */
@SpringBootTest
class NodePlacementHoldServiceTest {

    @Autowired NodePlacementHoldService service;
    @Autowired NodePlacementHoldRepository repository;

    @AfterEach
    void clean() {
        repository.findAll().stream().filter(h -> h.getNodeId().startsWith("nhs-")).forEach(repository::delete);
    }

    @DisplayName("중복 검사를 지나친 동시 등록은 500 이 아니라 409 다 — 유니크 제약 경합")
    @Test
    void concurrent_duplicate_is_conflict_not_500() {
        service.create(new NodePlacementHoldRequest("nhs-race:9100", null));
        // 다른 요청이 검사와 저장 사이에 끼어든 상태를 흉내낸다 — 검사를 건너뛰고 저장만 시도
        BusinessException e = assertThrows(BusinessException.class,
                () -> service.saveOrConflict(new NodePlacementHold("nhs-race:9100")));
        assertEquals(409, e.getStatus().value());
        assertTrue(e.getMessage().contains("동시에"));
    }
}
