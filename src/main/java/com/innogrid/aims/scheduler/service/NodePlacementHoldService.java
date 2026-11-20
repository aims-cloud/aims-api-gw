package com.innogrid.aims.scheduler.service;

import java.util.List;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.innogrid.aims.common.exception.BusinessException;
import com.innogrid.aims.scheduler.dto.NodePlacementHoldRequest;
import com.innogrid.aims.scheduler.entity.NodePlacementHold;
import com.innogrid.aims.scheduler.repository.NodePlacementHoldRepository;

import tools.jackson.databind.JsonNode;

/**
 * 노드 배치 중지 원장 — 검증 · 저장 규칙.
 *
 * <p>거부하는 값은 저장하면 확실히 틀린 것뿐이다(다른 원장과 같은 원칙) — 빈 노드 식별자, 길이 초과, 문자열이 아닌 사유.
 * 노드 식별자의 존재는 검증하지 않는다. 같은 노드를 두 번 등록하면 409, 수정에서 노드를 바꾸려 하면 400이다.</p>
 *
 * <p>노드 식별자 중복은 DB 유니크 제약이 잡지만 그때는 500으로 새므로 먼저 조회해 409를 낸다.</p>
 */
@Service
public class NodePlacementHoldService {

    private final NodePlacementHoldRepository repository;
    private final ScheduleActorResolver actorResolver;

    public NodePlacementHoldService(NodePlacementHoldRepository repository, ScheduleActorResolver actorResolver) {
        this.repository = repository;
        this.actorResolver = actorResolver;
    }

    // ── 조회 ───────────────────────────────────────────────────

    /** 목록. {@code nodeId}를 주면 그 문자열을 포함하는 노드만(부분 일치, 대소문자 무시). */
    @Transactional(readOnly = true)
    public List<NodePlacementHold> list(String nodeId) {
        List<NodePlacementHold> all = repository.findAllByOrderByNodeIdAsc();
        if (nodeId == null || nodeId.isBlank()) return all;
        String needle = nodeId.trim().toLowerCase();
        return all.stream().filter(h -> h.getNodeId().toLowerCase().contains(needle)).toList();
    }

    /** 없으면 404. */
    @Transactional(readOnly = true)
    public NodePlacementHold require(Long id) {
        return repository.findById(id)
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND, "노드 배치 중지를 찾을 수 없습니다: " + id));
    }

    // ── 변경 ───────────────────────────────────────────────────

    @Transactional
    public NodePlacementHold create(NodePlacementHoldRequest r) {
        if (r == null) throw badRequest("요청 본문이 필요합니다");
        String nodeId = requireText(r.nodeId());
        repository.findByNodeId(nodeId).ifPresent(existing -> { throw conflict(existing); });

        NodePlacementHold hold = new NodePlacementHold(nodeId);
        applyReason(hold, r.reason());
        ScheduleActorResolver.Actor actor = actorResolver.current();
        hold.setCreatedBy(actor.email(), actor.name());
        hold.setUpdatedBy(actor.email(), actor.name());
        return saveOrConflict(hold);
    }

    /**
     * 사유만 고치는 부분 수정. {@code nodeId}를 보내면 지금 값과 같아야 한다 — 노드를 바꾸면 중지 시작 시각의 뜻이 깨지므로
     * 해제(DELETE) 뒤 새로 등록하게 한다.
     */
    @Transactional
    public NodePlacementHold update(Long id, NodePlacementHoldRequest r) {
        if (r == null) throw badRequest("요청 본문이 필요합니다");
        NodePlacementHold hold = require(id);
        if (r.nodeId() != null && !r.nodeId().trim().equals(hold.getNodeId())) {
            throw badRequest(String.format("배치 중지한 노드는 바꿀 수 없습니다(지금 %s) — 해제(DELETE) 뒤 새로 등록하세요",
                    hold.getNodeId()));
        }
        applyReason(hold, r.reason());
        ScheduleActorResolver.Actor actor = actorResolver.current();
        hold.setUpdatedBy(actor.email(), actor.name());
        return saveOrConflict(hold);
    }

    /** 해제. 없으면 404. 다음 배치부터 그 노드는 다시 후보가 된다. */
    @Transactional
    public void delete(Long id) {
        repository.delete(require(id));
    }

    /**
     * 저장하며 동시 등록 경합을 409로 바꾼다. 중복은 먼저 조회해 막지만 같은 노드를 동시에 등록하면 그 검사를 지나쳐
     * 유니크 제약에 걸린다. 제약 위반 뒤의 영속성 컨텍스트는 믿을 수 없어 다시 조회하지 않고 어떤 노드가 걸렸는지만 알린다.
     */
    NodePlacementHold saveOrConflict(NodePlacementHold hold) {
        try {
            return repository.saveAndFlush(hold);
        } catch (DataIntegrityViolationException race) {
            throw new BusinessException(HttpStatus.CONFLICT, String.format(
                    "이미 배치를 중지한 노드입니다: %s (동시에 등록되어 유니크 제약에 걸렸습니다)", hold.getNodeId()));
        }
    }

    // ── 반영 · 검증 ────────────────────────────────────────────

    private static void applyReason(NodePlacementHold hold, JsonNode node) {
        if (node == null) return;
        if (node.isNull()) {
            hold.setReason(null);
            return;
        }
        if (!node.isString()) throw badRequest("사유(reason)는 문자열이어야 합니다");
        String reason = node.asString().trim();
        hold.setReason(reason.isEmpty() ? null : limit(reason, "사유(reason)", NodePlacementHold.REASON_MAX));
    }

    private static String requireText(String value) {
        if (value == null || value.isBlank()) throw badRequest("노드 식별자(nodeId)는 필수입니다");
        return limit(value.trim(), "노드 식별자(nodeId)", NodePlacementHold.NODE_ID_MAX);
    }

    private static String limit(String value, String label, int max) {
        if (value.length() > max) {
            throw badRequest(String.format("%s은(는) %d자 이내여야 합니다 (현재 %d자)", label, max, value.length()));
        }
        return value;
    }

    private static BusinessException conflict(NodePlacementHold existing) {
        return new BusinessException(HttpStatus.CONFLICT, String.format(
                "이미 배치를 중지한 노드입니다: %s (id %d). 사유를 고치려면 PUT /node-placement-holds/%d 를 쓰세요",
                existing.getNodeId(), existing.getId(), existing.getId()));
    }

    private static BusinessException badRequest(String message) {
        return new BusinessException(HttpStatus.BAD_REQUEST, message);
    }
}
