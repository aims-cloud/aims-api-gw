package com.innogrid.aims.scheduler.controller;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import org.springframework.data.domain.Limit;

import com.innogrid.aims.scheduler.entity.SchedulingHistory;
import com.innogrid.aims.scheduler.repository.SchedulingHistoryRepository;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 배열로 전량 반환하던 두 이력 API 의 반환 상한.
 *
 * <p>{@code scheduling_history} 는 연구 근거라 <b>지우지 않는다</b> — 즉 단조 증가한다. 그런데 두 API
 * ({@code /history}, {@code /workloads/{id}/history})는 표 전체를 배열로 돌려주고 있었다. 행이 늘면
 * 응답 하나가 표 전체가 되고, 서버는 그만큼을 한꺼번에 메모리에 올린다.</p>
 *
 * <p>응답 <b>형태</b>는 배열 그대로 두고(프런트 영향 없음) 건수만 자른다. 그래서 이 테스트는 형태가 아니라
 * <b>건수</b>를 고정한다. 상한을 지우거나 올리면 여기서 걸린다.</p>
 *
 * <p>상한보다 5건 많게 심고 끝에 지운다. 다른 테스트가 남긴 이력이 있어도 결과가 달라지지 않는다 —
 * 전체 이력은 이미 상한을 넘기 때문이다.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@WithMockUser
class HistoryReturnLimitTest {

    /** {@code SchedulerController.HISTORY_MAX} 와 같아야 한다. 다르면 아래 단언이 실패한다. */
    private static final int EXPECTED_LIMIT = 1000;
    private static final int SEEDED = EXPECTED_LIMIT + 5;
    private static final long WORKLOAD = 9_910_001L;

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired SchedulingHistoryRepository repository;

    private final List<Long> seeded = new ArrayList<>();

    @BeforeEach
    void seed() {
        List<SchedulingHistory> rows = new ArrayList<>(SEEDED);
        for (int i = 0; i < SEEDED; i++) {
            rows.add(new SchedulingHistory(WORKLOAD, "limit-" + i, "mdc-01", "n1",
                    0.5, "r", "weighted", "default"));
        }
        repository.saveAll(rows).forEach(h -> seeded.add(h.getId()));
    }

    @AfterEach
    void clean() {
        repository.deleteAllById(seeded);
        seeded.clear();
    }

    @DisplayName("전체 이력은 상한 건수까지만 돌려준다 — 응답은 여전히 배열이다")
    @Test
    void full_history_is_capped() throws Exception {
        JsonNode data = fetch("/api/v1/scheduler/history");

        assertTrue(data.isArray(), "응답 형태가 배열에서 바뀌면 프런트가 영향을 받는다");
        assertEquals(EXPECTED_LIMIT, data.size(),
                "심은 이력이 " + SEEDED + "건인데 상한이 걸리지 않았다 — 표가 커질수록 응답도 같이 커진다");
    }

    @DisplayName("워크로드별 이력도 같은 상한을 따른다")
    @Test
    void workload_history_is_capped() throws Exception {
        JsonNode data = fetch("/api/v1/scheduler/workloads/" + WORKLOAD + "/history");

        assertTrue(data.isArray());
        assertEquals(EXPECTED_LIMIT, data.size(),
                "한 워크로드에만 " + SEEDED + "건을 심었는데 전부 돌아왔다");
    }

    @DisplayName("상한 아래면 그대로 다 돌려준다 — 자르기가 평소 조회를 건드리지 않는다")
    @Test
    void below_limit_returns_everything() throws Exception {
        long other = WORKLOAD + 1;
        List<Long> few = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            few.add(repository.save(new SchedulingHistory(other, "few-" + i, "mdc-01", "n1",
                    0.5, "r", "weighted", "default")).getId());
        }
        try {
            assertEquals(3, fetch("/api/v1/scheduler/workloads/" + other + "/history").size());
        } finally {
            repository.deleteAllById(few);
        }
    }

    @DisplayName("자를 때 남는 쪽이 최신이다 — 정렬한 뒤에 자른다")
    @Test
    void limit_keeps_the_newest_not_the_oldest() {
        long other = WORKLOAD + 2;
        List<Long> rows = new ArrayList<>();
        // 저장 사이에 **시계가 실제로 넘어갈 때까지** 기다린다.
        // 처음에는 "저장마다 DB 왕복이 있으니 시각이 달라지겠지" 하고 그냥 이어 저장했는데, 세 건이 같은 시각으로
        // 찍혀 정렬 순서가 매번 달라졌다(반복 실행에서 4회 중 2회 실패). H2 왕복이 시계 해상도보다 빠르다.
        // 고정 sleep 대신 시각이 바뀌는 즉시 진행해, 느려지지 않으면서도 결과가 확정되게 한다.
        for (String name : List.of("oldest", "middle", "newest")) {
            SchedulingHistory saved = repository.save(new SchedulingHistory(other, name, "mdc-01", "n1",
                    0.5, "r", "weighted", "default"));
            rows.add(saved.getId());
            waitUntilClockPasses(saved.getCreatedAt());
        }
        try {
            List<String> kept = repository.findByWorkloadIdOrderByCreatedAtDesc(other, Limit.of(2))
                    .stream().map(SchedulingHistory::getWorkloadName).toList();

            assertEquals(List.of("newest", "middle"), kept,
                    "자른 뒤에 정렬하면 오래된 것이 남는다 — 정렬이 먼저여야 한다");
        } finally {
            repository.deleteAllById(rows);
        }
    }

    /** 주어진 시각보다 뒤가 될 때까지 기다린다 — 같은 시각이 두 번 찍히면 정렬 순서가 정해지지 않는다. */
    private static void waitUntilClockPasses(OffsetDateTime moment) {
        while (!OffsetDateTime.now().isAfter(moment)) {
            Thread.onSpinWait();
        }
    }

    private JsonNode fetch(String path) throws Exception {
        String body = mockMvc.perform(get(path))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).get("data");
    }
}
