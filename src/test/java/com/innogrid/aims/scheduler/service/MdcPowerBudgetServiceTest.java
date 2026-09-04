package com.innogrid.aims.scheduler.service;

import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import com.innogrid.aims.common.exception.BusinessException;
import com.innogrid.aims.scheduler.dto.MdcPowerBudgetRequest;
import com.innogrid.aims.scheduler.entity.MdcPowerBudget;
import com.innogrid.aims.scheduler.model.MdcPowerBudgets;
import com.innogrid.aims.scheduler.repository.MdcPowerBudgetRepository;

import tools.jackson.databind.ObjectMapper;

import static org.junit.jupiter.api.Assertions.*;

/** MDC 전력 예산 원장의 검증 규칙과 조회 스냅샷. */
@SpringBootTest
class MdcPowerBudgetServiceTest {

    @Autowired MdcPowerBudgetService service;
    @Autowired MdcPowerBudgetRepository repository;
    @Autowired ObjectMapper objectMapper;

    @AfterEach
    void clean() {
        repository.findAll().stream().filter(b -> b.getMdcId().startsWith("mpb-")).forEach(repository::delete);
    }

    private MdcPowerBudgetRequest req(String mdcId, Double watts, String source, String description) {
        return new MdcPowerBudgetRequest(mdcId, watts, source,
                description == null ? null : objectMapper.readTree(description));
    }

    @DisplayName("등록하면 기본 출처는 MANUAL 이고 감사 계정이 남는다")
    @Test
    void create_defaults_manual_with_audit() {
        MdcPowerBudget b = service.create(req("mpb-seoul", 4000.0, null, "\"수전 계약 4kW\""));
        assertNotNull(b.getId());
        assertEquals("mpb-seoul", b.getMdcId());
        assertEquals(4000.0, b.getBudgetWatts());
        assertEquals(MdcPowerBudget.BudgetSource.MANUAL, b.getSource());
        assertEquals("수전 계약 4kW", b.getDescription());
        assertNotNull(b.getCreatedAt());
        assertNotNull(b.getCreatedBy(), "감사 계정이 남는다");
    }

    @DisplayName("예산은 0 보다 크고 유한해야 한다 — 0·음수·NaN·무한대는 400, 크기 상한은 없다")
    @Test
    void budget_must_be_positive_and_finite_without_upper_bound() {
        assertEquals(400, assertThrows(BusinessException.class, () -> service.create(req("mpb-b", 0.0, null, null))).getStatus().value());
        assertEquals(400, assertThrows(BusinessException.class, () -> service.create(req("mpb-b", -1.0, null, null))).getStatus().value());
        assertEquals(400, assertThrows(BusinessException.class, () -> service.create(req("mpb-b", Double.NaN, null, null))).getStatus().value());
        assertEquals(400, assertThrows(BusinessException.class, () -> service.create(req("mpb-b", Double.POSITIVE_INFINITY, null, null))).getStatus().value());
        assertEquals(400, assertThrows(BusinessException.class, () -> service.create(req("mpb-b", null, null, null))).getStatus().value());
        // 상한이 없다 — MDC 한 곳이 수백 kW 여도 받는다
        assertEquals(450_000.0, service.create(req("mpb-huge", 450_000.0, null, null)).getBudgetWatts());
    }

    @DisplayName("MDC 식별자는 필수이고 모르는 출처는 400 이다")
    @Test
    void mdc_id_required_and_source_parsed() {
        assertEquals(400, assertThrows(BusinessException.class, () -> service.create(req(" ", 100.0, null, null))).getStatus().value());
        assertEquals(400, assertThrows(BusinessException.class, () -> service.create(req("mpb-c", 100.0, "GUESS", null))).getStatus().value());
        assertEquals(MdcPowerBudget.BudgetSource.SPEC_SHEET,
                service.create(req("mpb-c", 100.0, "spec_sheet", null)).getSource(), "출처는 대소문자를 가리지 않는다");
    }

    @DisplayName("같은 MDC 를 다시 등록하면 409 다")
    @Test
    void duplicate_mdc_conflicts() {
        service.create(req("mpb-dup", 1000.0, null, null));
        assertEquals(409, assertThrows(BusinessException.class, () -> service.create(req("mpb-dup", 2000.0, null, null))).getStatus().value());
    }

    @DisplayName("부분 수정은 보낸 키만 바꾸고 설명에 null 을 주면 비운다")
    @Test
    void update_applies_only_present_keys() {
        MdcPowerBudget created = service.create(req("mpb-upd", 1000.0, "SPEC_SHEET", "\"처음\""));

        MdcPowerBudget changed = service.update(created.getId(), req(null, 2500.0, null, null));
        assertEquals(2500.0, changed.getBudgetWatts());
        assertEquals(MdcPowerBudget.BudgetSource.SPEC_SHEET, changed.getSource(), "보내지 않은 출처는 유지된다");
        assertEquals("처음", changed.getDescription(), "보내지 않은 설명은 유지된다");

        assertNull(service.update(created.getId(), req(null, null, null, "null")).getDescription());
        assertNotNull(changed.getUpdatedBy(), "수정 감사 계정이 남는다");
    }

    @DisplayName("없는 예산을 조회·수정·삭제하면 404 다")
    @Test
    void missing_budget_is_not_found() {
        assertEquals(404, assertThrows(BusinessException.class, () -> service.require(-1L)).getStatus().value());
        assertEquals(404, assertThrows(BusinessException.class, () -> service.delete(-1L)).getStatus().value());
    }

    @DisplayName("목록은 MDC 식별자 순이고 부분 일치로 거를 수 있다")
    @Test
    void list_is_ordered_and_filterable() {
        service.create(req("mpb-zulu", 100.0, null, null));
        service.create(req("mpb-alpha", 200.0, null, null));

        List<String> mine = service.list("mpb-").stream().map(MdcPowerBudget::getMdcId).toList();
        assertEquals(List.of("mpb-alpha", "mpb-zulu"), mine);
        assertEquals(1, service.list("ALPHA").size(), "부분 일치는 대소문자를 가리지 않는다");
    }

    @DisplayName("조회 스냅샷은 등록된 MDC 만 예산을 돌려준다")
    @Test
    void snapshot_reflects_ledger() {
        service.create(req("mpb-snap", 3000.0, null, null));
        MdcPowerBudgets snapshot = MdcPowerBudgets.of(repository.findAll());

        assertEquals(3000.0, snapshot.budgetOf("mpb-snap").orElseThrow());
        assertTrue(snapshot.budgetOf("mpb-none").isEmpty());
    }
}
