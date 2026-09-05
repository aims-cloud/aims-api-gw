package com.innogrid.aims.scheduler.controller;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.Limit;

import com.innogrid.aims.scheduler.repository.SchedulingHistoryRepository;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;

/**
 * 이력 조회가 <b>저장소에 어떤 상한을 넘기는지</b>를 고정한다.
 *
 * <p><b>왜 응답만 봐서는 부족한가</b> — 상한을 저장소에 넘기지 않고 전량을 읽어 온 뒤 자바에서 잘라도
 * <b>응답 바이트는 완전히 똑같다.</b> 그러면 "응답 하나가 표 전체가 되는 것"은 막아도 <b>DB 왕복에서는
 * 여전히 표 전체를 정렬해 메모리에 올린다</b> — 이 변경이 없애려던 비용의 절반이 그대로 남는다.
 * 그래서 넘어가는 {@code Limit} 값 자체를 잡아서 본다.</p>
 *
 * <p>상한보다 <b>한 건 더</b> 읽는 것도 의도다. 딱 상한만 읽으면 "정말 잘렸는지"와 "우연히 상한과 같은
 * 건수인지"를 구분할 수 없어 잘리지 않았는데도 경고가 찍힌다.</p>
 *
 * <p>스프링 컨텍스트를 띄우지 않는다 — 컨트롤러를 직접 만들고 저장소만 대역으로 둔다.</p>
 */
class HistoryQueryLimitTest {

    /** {@code SchedulerController.HISTORY_MAX} 와 같아야 한다. */
    private static final int HISTORY_MAX = 1000;

    private final SchedulingHistoryRepository repository = mock(SchedulingHistoryRepository.class);
    private final SchedulerController controller =
            new SchedulerController(null, null, null, null, repository, null);

    @DisplayName("전체 이력 조회는 상한보다 한 건 더 읽는 상한을 저장소에 넘긴다")
    @Test
    void full_history_passes_probe_limit_to_repository() {
        when(repository.findAllByOrderByCreatedAtDesc(any())).thenReturn(List.of());

        controller.getHistory();

        assertEquals(HISTORY_MAX + 1, capturedLimit().max(),
                "상한을 저장소에 넘기지 않으면 응답만 작아지고 DB 는 여전히 표 전체를 읽는다");
    }

    @DisplayName("워크로드별 이력 조회도 같은 상한을 저장소에 넘긴다")
    @Test
    void workload_history_passes_probe_limit_to_repository() {
        when(repository.findByWorkloadIdOrderByCreatedAtDesc(anyLong(), any())).thenReturn(List.of());

        controller.getWorkloadHistory(42L);

        ArgumentCaptor<Limit> captor = ArgumentCaptor.forClass(Limit.class);
        verify(repository).findByWorkloadIdOrderByCreatedAtDesc(eq(42L), captor.capture());
        assertEquals(HISTORY_MAX + 1, captor.getValue().max());
    }

    private Limit capturedLimit() {
        ArgumentCaptor<Limit> captor = ArgumentCaptor.forClass(Limit.class);
        verify(repository).findAllByOrderByCreatedAtDesc(captor.capture());
        return captor.getValue();
    }
}
