package com.innogrid.aims.scheduler.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import com.innogrid.aims.scheduler.entity.SchedulingPolicy;
import com.innogrid.aims.scheduler.model.PolicyChoice;
import com.innogrid.aims.scheduler.entity.Workload.ResourceType;
import com.innogrid.aims.scheduler.repository.SchedulingPolicyRepository;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 활성 정책 선택 규칙 — 둘 이상이면 이름순으로 결정적, 활성이 없으면 {@code default} 를 <b>다시 저장하지 않는다</b>.
 *
 * <p>예전 {@code resolvePolicy} 는 공통 활성이 없으면 {@code new SchedulingPolicy("default")} 를 저장했다. 이름이 유니크라
 * 비활성 {@code default} 가 이미 있으면 유니크 위반으로 배치 경로 전체가 500 이었다(단계 13 점검, 2026-09-02).</p>
 */
@SpringBootTest
@Transactional
class PolicyResolutionRulesTest {

    @Autowired SchedulingService schedulingService;
    @Autowired SchedulingPolicyRepository repository;

    @BeforeEach
    void clean() {
        // 트랜잭션 안에서는 Hibernate 가 INSERT 를 DELETE 보다 먼저 내보낸다. 다른 테스트가 남긴 "default" 행이 있으면
        // 아래 테스트의 같은 이름 저장이 삭제보다 먼저 실행되어 유니크 위반이 나므로, 삭제를 즉시 반영한다.
        repository.deleteAll();
        repository.flush();
    }

    @DisplayName("같은 종류 활성이 둘이면 이름순 첫 번째로 결정된다")
    @Test
    void two_active_same_type_first_by_name() {
        repository.save(active("zeta", ResourceType.GPU));
        repository.save(active("alpha", ResourceType.GPU));
        repository.save(active("mid", ResourceType.GPU));

        assertEquals("alpha", schedulingService.resolvePolicy(ResourceType.GPU).getName());
        assertEquals("alpha", schedulingService.resolvePolicy(ResourceType.GPU).getName(), "반복해도 같다");
    }

    @DisplayName("공통 활성이 둘이어도 이름순이고 종류별 활성이 우선한다")
    @Test
    void two_active_common_by_name_type_first() {
        repository.save(active("common-b", null));
        repository.save(active("common-a", null));
        repository.save(active("gpu", ResourceType.GPU));

        assertEquals("common-a", schedulingService.resolvePolicy().getName());
        assertEquals("common-a", schedulingService.resolvePolicy(ResourceType.VM).getName());
        assertEquals("gpu", schedulingService.resolvePolicy(ResourceType.GPU).getName());
    }

    @DisplayName("비활성 default 가 있으면 다시 저장하지 않고 그 값을 쓴다 예전엔 유니크 위반 500")
    @Test
    void inactive_default_reused() {
        SchedulingPolicy off = new SchedulingPolicy(SchedulingService.DEFAULT_POLICY_NAME, "기본");
        off.setActive(false);
        off.setCpuWeight(0.9);
        repository.save(off);

        SchedulingPolicy used = assertDoesNotThrow(() -> schedulingService.resolvePolicy(ResourceType.VM));

        assertEquals("default", used.getName());
        assertEquals(0.9, used.getCpuWeight(), "저장된 default 의 값");
        assertEquals(1, repository.count(), "새 행을 만들지 않는다");
        assertFalse(repository.findByName("default").orElseThrow().isActive(), "재활성화하지 않는다");
    }

    @DisplayName("아무 정책도 없으면 예전처럼 default 를 만들어 저장한다")
    @Test
    void no_policy_creates_default() {
        assertEquals(0, repository.count());

        SchedulingPolicy created = schedulingService.resolvePolicy(ResourceType.NPU);

        assertEquals("default", created.getName());
        assertTrue(created.isActive());
        assertEquals(1, repository.count());
    }

    private static SchedulingPolicy active(String name, ResourceType type) {
        SchedulingPolicy p = new SchedulingPolicy(name, "d");
        p.setResourceType(type);
        p.setActive(true);
        return p;
    }

    @DisplayName("저장 없는 선택은 단계를 밝히고 정책이 없을 때도 행을 만들지 않는다")
    @Test
    void choose_policy_reports_source_without_saving() {
        PolicyChoice none = schedulingService.choosePolicy(ResourceType.VM);
        assertEquals(PolicyChoice.Source.DEFAULT, none.source());
        assertFalse(none.persisted(), "저장하지 않은 기본값");
        assertEquals("default", none.policy().getName());
        assertEquals(0, repository.count(), "읽기 선택은 행을 만들지 않는다");

        repository.save(active("common", null));
        repository.save(active("gpu", ResourceType.GPU));
        assertEquals(PolicyChoice.Source.COMMON, schedulingService.choosePolicy(ResourceType.VM).source());
        assertEquals(PolicyChoice.Source.RESOURCE_TYPE, schedulingService.choosePolicy(ResourceType.GPU).source());
        assertTrue(schedulingService.choosePolicy(ResourceType.GPU).persisted());
        assertEquals(2, repository.count());
    }
}
