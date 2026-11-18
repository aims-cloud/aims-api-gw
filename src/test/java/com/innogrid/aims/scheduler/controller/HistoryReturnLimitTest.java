package com.innogrid.aims.scheduler.controller;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.data.domain.Limit;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import com.innogrid.aims.scheduler.entity.SchedulingHistory;
import com.innogrid.aims.scheduler.repository.SchedulingHistoryRepository;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 배열로 돌려주는 두 이력 API({@code /history}, {@code /workloads/{id}/history})의 반환 상한.
 *
 * <p>상한을 두는 까닭은 {@code SchedulerController.HISTORY_MAX}에 있다. 응답 <b>형태</b>는 배열 그대로 두고
 * 건수만 자르므로, 이 테스트는 <b>건수와 "어느 쪽이 남는가"</b>를 확인한다.</p>
 *
 * <p>건수만 세면 자르는 방향이 뒤집혀도(오래된 쪽을 남겨도) 통과한다. 그래서 상한보다 몇 건 많게 심되
 * <b>마지막 몇 건은 시각이 확실히 뒤가 되도록</b> 한 건씩 저장하고, 잘린 결과에 그 몇 건이 전부 살아 있는지 본다.
 * 그래야 "정렬한 뒤에 자른다"가 증명된다.</p>
 *
 * <p>심은 것은 끝에 전부 지운다. 전체 이력은 이미 상한을 넘으므로 다른 테스트가 남긴 이력이 있어도 결과가 같다.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@WithMockUser
class HistoryReturnLimitTest {

    /** {@code SchedulerController.HISTORY_MAX}와 같아야 한다. 다르면 아래 확인이 실패한다. */
    private static final int EXPECTED_LIMIT = 1000;
    /** 상한을 넘겼다는 것과 "최신이 남는다"를 함께 보기 위해 상한보다 이만큼 더 심는다. */
    private static final int EXTRA = 5;
    private static final long WORKLOAD = 9_910_001L;

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired SchedulingHistoryRepository repository;

    private final List<Long> seeded = new ArrayList<>();
    /** 확실히 가장 나중에 저장한 이름들 — 잘린 뒤에도 전부 남아 있어야 한다. */
    private final Set<String> newest = new LinkedHashSet<>();

    @BeforeEach
    void seed() {
        List<SchedulingHistory> bulk = new ArrayList<>(EXPECTED_LIMIT);
        for (int i = 0; i < EXPECTED_LIMIT; i++) {
            bulk.add(row(WORKLOAD, "bulk-" + i));
        }
        repository.saveAll(bulk).forEach(h -> seeded.add(h.getId()));

        // 여기부터는 한 건씩 저장하고, 저장 사이에 **시계가 실제로 넘어갈 때까지** 기다린다.
        // 한꺼번에 저장하면 같은 시각이 찍혀 "어느 것이 최신인가"가 정해지지 않는다(실제로 겪었다).
        for (int i = 0; i < EXTRA; i++) {
            String name = "newest-" + i;
            SchedulingHistory saved = repository.save(row(WORKLOAD, name));
            seeded.add(saved.getId());
            newest.add(name);
            waitUntilClockPasses(saved.getCreatedAt());
        }
    }

    @AfterEach
    void clean() {
        repository.deleteAllById(seeded);
        seeded.clear();
        newest.clear();
    }

    @DisplayName("전체 이력은 상한까지만 돌려주고 남는 쪽은 최신이다 — 응답은 여전히 배열이다")
    @Test
    void full_history_is_capped_keeping_the_newest() throws Exception {
        JsonNode data = fetch("/api/v1/scheduler/history");

        assertTrue(data.isArray(), "응답 형태가 배열에서 바뀌면 프런트가 영향을 받는다");
        assertEquals(EXPECTED_LIMIT, data.size(),
                "심은 이력이 " + (EXPECTED_LIMIT + EXTRA) + "건인데 상한이 걸리지 않았다");
        assertTrue(names(data).containsAll(newest),
                "가장 나중에 심은 " + EXTRA + "건이 잘려 나갔다 — 오래된 쪽을 남기고 있다는 뜻이다");
    }

    @DisplayName("워크로드별 이력도 같은 상한을 따르고 최신을 남긴다")
    @Test
    void workload_history_is_capped_keeping_the_newest() throws Exception {
        JsonNode data = fetch("/api/v1/scheduler/workloads/" + WORKLOAD + "/history");

        assertTrue(data.isArray());
        assertEquals(EXPECTED_LIMIT, data.size(),
                "한 워크로드에만 " + (EXPECTED_LIMIT + EXTRA) + "건을 심었는데 상한이 걸리지 않았다");
        assertTrue(names(data).containsAll(newest),
                "가장 나중에 심은 " + EXTRA + "건이 잘려 나갔다");
    }

    @DisplayName("상한 아래면 그대로 다 돌려준다 — 자르기가 평소 조회를 건드리지 않는다")
    @Test
    void below_limit_returns_everything() throws Exception {
        long other = WORKLOAD + 1;
        List<Long> few = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            few.add(repository.save(row(other, "few-" + i)).getId());
        }
        try {
            assertEquals(3, fetch("/api/v1/scheduler/workloads/" + other + "/history").size());
        } finally {
            repository.deleteAllById(few);
        }
    }

    @DisplayName("정확히 상한 건수면 자르지 않는다 — 경계에서 한 건도 잃지 않는다")
    @Test
    void exactly_at_limit_returns_everything() throws Exception {
        long other = WORKLOAD + 3;
        List<Long> rows = new ArrayList<>();
        List<SchedulingHistory> bulk = new ArrayList<>(EXPECTED_LIMIT);
        for (int i = 0; i < EXPECTED_LIMIT; i++) {
            bulk.add(row(other, "edge-" + i));
        }
        repository.saveAll(bulk).forEach(h -> rows.add(h.getId()));
        try {
            // 초과가 아니라 "딱 상한"이다. 자르는 조건을 > 에서 >= 로 잘못 바꾸면 여기서 한 건이 사라진다.
            assertEquals(EXPECTED_LIMIT,
                    fetch("/api/v1/scheduler/workloads/" + other + "/history").size(),
                    "정확히 상한 건수인데 잘렸다 — 자르는 조건이 한 칸 어긋났다");
        } finally {
            repository.deleteAllById(rows);
        }
    }

    @DisplayName("저장소도 정렬한 뒤에 자른다 — 자르고 정렬하면 오래된 것이 남는다")
    @Test
    void repository_sorts_before_limiting() {
        long other = WORKLOAD + 2;
        List<Long> rows = new ArrayList<>();
        for (String name : List.of("oldest", "middle", "latest")) {
            SchedulingHistory saved = repository.save(row(other, name));
            rows.add(saved.getId());
            waitUntilClockPasses(saved.getCreatedAt());
        }
        try {
            List<String> kept = repository.findByWorkloadIdOrderByCreatedAtDesc(other, Limit.of(2))
                    .stream().map(SchedulingHistory::getWorkloadName).toList();

            assertEquals(List.of("latest", "middle"), kept);
        } finally {
            repository.deleteAllById(rows);
        }
    }

    // ── 헬퍼 ───────────────────────────────────────────────────

    private SchedulingHistory row(long workloadId, String name) {
        return new SchedulingHistory(workloadId, name, "mdc-01", "n1", 0.5, "r", "weighted", "default");
    }

    private Set<String> names(JsonNode array) {
        Set<String> found = new LinkedHashSet<>();
        array.forEach(node -> found.add(node.get("workloadName").asString()));
        return found;
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
