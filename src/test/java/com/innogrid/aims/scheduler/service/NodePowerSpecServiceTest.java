package com.innogrid.aims.scheduler.service;

import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import com.innogrid.aims.common.exception.BusinessException;
import com.innogrid.aims.scheduler.dto.NodePowerSpecRequest;
import com.innogrid.aims.scheduler.entity.NodePowerSpec;
import com.innogrid.aims.scheduler.model.NodePowerSpecs;
import com.innogrid.aims.scheduler.repository.NodePowerSpecRepository;

import tools.jackson.databind.ObjectMapper;

import static org.junit.jupiter.api.Assertions.*;

/** 전력 사양 원장의 검증 규칙과 조회 스냅샷. */
@SpringBootTest
class NodePowerSpecServiceTest {

    @Autowired NodePowerSpecService service;
    @Autowired NodePowerSpecRepository repository;
    @Autowired ObjectMapper objectMapper;

    @AfterEach
    void clean() {
        repository.findAll().stream().filter(s -> s.getNodeId().startsWith("nps-")).forEach(repository::delete);
    }

    private NodePowerSpecRequest req(String nodeId, Double watts, String source, String description) {
        return new NodePowerSpecRequest(nodeId, watts, source,
                description == null ? null : objectMapper.readTree(description));
    }

    @DisplayName("등록하면 기본 출처는 MANUAL 이고 감사 계정이 남는다")
    @Test
    void create_defaults_manual_with_audit() {
        NodePowerSpec s = service.create(req("nps-a:9100", 900.0, null, "\"사양서 기준\""));
        assertNotNull(s.getId());
        assertEquals("nps-a:9100", s.getNodeId());
        assertEquals(900.0, s.getCapacityWatts());
        assertEquals(NodePowerSpec.PowerSpecSource.MANUAL, s.getSource());
        assertEquals("사양서 기준", s.getDescription());
        assertNotNull(s.getCreatedAt());
        assertNotNull(s.getCreatedBy(), "감사 계정이 남는다");
    }

    @DisplayName("정격은 0 보다 크고 유한해야 한다 — 0·음수·NaN·무한대는 400, 크기 상한은 없다")
    @Test
    void capacity_must_be_positive_and_finite_without_upper_bound() {
        assertEquals(400, assertThrows(BusinessException.class, () -> service.create(req("nps-b:9100", 0.0, null, null))).getStatus().value());
        assertEquals(400, assertThrows(BusinessException.class, () -> service.create(req("nps-b:9100", -1.0, null, null))).getStatus().value());
        assertEquals(400, assertThrows(BusinessException.class, () -> service.create(req("nps-b:9100", Double.NaN, null, null))).getStatus().value());
        assertEquals(400, assertThrows(BusinessException.class, () -> service.create(req("nps-b:9100", Double.POSITIVE_INFINITY, null, null))).getStatus().value());
        assertEquals(400, assertThrows(BusinessException.class, () -> service.create(req("nps-b:9100", null, null, null))).getStatus().value());
        // 상한이 없다 — 랙·MDC 단위로 등록해도 받는다
        assertEquals(60_000.0, service.create(req("nps-b:9100", 60_000.0, null, null)).getCapacityWatts());
    }

    @DisplayName("노드 식별자는 필수·100자 이내이고 같은 노드를 다시 등록하면 409")
    @Test
    void node_id_required_and_unique() {
        assertEquals(400, assertThrows(BusinessException.class, () -> service.create(req("  ", 500.0, null, null))).getStatus().value());
        assertEquals(400, assertThrows(BusinessException.class, () -> service.create(req("nps-" + "x".repeat(100), 500.0, null, null))).getStatus().value());
        service.create(req("nps-dup:9100", 500.0, null, null));
        BusinessException e = assertThrows(BusinessException.class, () -> service.create(req("nps-dup:9100", 700.0, null, null)));
        assertEquals(409, e.getStatus().value());
        assertTrue(e.getMessage().contains("이미 등록된 노드"));
    }

    @DisplayName("출처는 대소문자를 무시하고 모르는 값은 400")
    @Test
    void source_lenient_parse() {
        assertEquals(NodePowerSpec.PowerSpecSource.SPEC_SHEET, service.create(req("nps-s1:9100", 500.0, "spec_sheet", null)).getSource());
        assertEquals(NodePowerSpec.PowerSpecSource.MEASURED, service.create(req("nps-s2:9100", 500.0, "MEASURED", null)).getSource());
        assertEquals(400, assertThrows(BusinessException.class, () -> service.create(req("nps-s3:9100", 500.0, "guess", null))).getStatus().value());
    }

    @DisplayName("부분 수정은 보낸 키만 반영하고 설명에 null 을 주면 비운다. 다른 노드의 식별자로 바꾸면 409")
    @Test
    void partial_update_and_conflict() {
        NodePowerSpec a = service.create(req("nps-u1:9100", 500.0, "SPEC_SHEET", "\"처음\""));
        NodePowerSpec b = service.create(req("nps-u2:9100", 600.0, null, null));

        NodePowerSpec kept = service.update(a.getId(), req(null, 800.0, null, null));
        assertEquals(800.0, kept.getCapacityWatts());
        assertEquals("nps-u1:9100", kept.getNodeId(), "보내지 않은 키는 유지");
        assertEquals(NodePowerSpec.PowerSpecSource.SPEC_SHEET, kept.getSource());
        assertEquals("처음", kept.getDescription());

        assertNull(service.update(a.getId(), req(null, null, null, "null")).getDescription(), "명시적 null 은 비움");
        assertEquals(409, assertThrows(BusinessException.class,
                () -> service.update(a.getId(), req(b.getNodeId(), null, null, null))).getStatus().value());
        assertEquals(404, assertThrows(BusinessException.class, () -> service.update(999_999L, req(null, 500.0, null, null))).getStatus().value());
    }

    @DisplayName("목록은 노드 식별자 순이고 부분 일치 필터가 걸린다. 삭제하면 404")
    @Test
    void list_filter_and_delete() {
        service.create(req("nps-z:9100", 500.0, null, null));
        NodePowerSpec first = service.create(req("nps-a:9100", 500.0, null, null));
        List<NodePowerSpec> mine = service.list("nps-").stream().toList();
        assertEquals(2, mine.size());
        assertEquals("nps-a:9100", mine.get(0).getNodeId(), "식별자 오름차순");
        assertEquals(1, service.list("NPS-Z").size(), "대소문자 무시 부분 일치");
        service.delete(first.getId());
        assertEquals(404, assertThrows(BusinessException.class, () -> service.require(first.getId())).getStatus().value());
    }

    @DisplayName("조회 스냅샷은 원장의 정격을 주고, 없는 노드는 넘겨준 대체값을 쓴다")
    @Test
    void snapshot_prefers_ledger_then_fallback() {
        service.create(req("nps-snap:9100", 1234.5, null, null));
        NodePowerSpecs specs = service.snapshot();
        assertEquals(1234.5, specs.capacityOf("nps-snap:9100").orElseThrow());
        assertTrue(specs.capacityOf("nps-missing:9100").isEmpty());
        assertEquals(1234.5, specs.capacityOr("nps-snap:9100", id -> 500.0));
        assertEquals(500.0, specs.capacityOr("nps-missing:9100", id -> 500.0), "원장에 없으면 대체값");
        assertTrue(NodePowerSpecs.EMPTY.isEmpty());
        assertEquals(500.0, NodePowerSpecs.EMPTY.capacityOr("any", id -> 500.0), "빈 원장은 항상 대체값");
    }
}
