package com.innogrid.aims.scheduler.service;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.innogrid.aims.common.exception.BusinessException;
import com.innogrid.aims.scheduler.dto.NodePowerSpecRequest;
import com.innogrid.aims.scheduler.entity.NodePowerSpec;
import com.innogrid.aims.scheduler.model.NodePowerSpecs;
import com.innogrid.aims.scheduler.repository.NodePowerSpecRepository;

import tools.jackson.databind.JsonNode;

/**
 * 노드 전력 사양 원장 — 검증·저장 규칙.
 *
 * <p>거부하는 값은 "저장하면 확실히 틀린 것"이다(링크 원장과 같은 원칙) — 빈 노드 식별자, 0 이하 정격,
 * NaN·무한대 정격, 모르는 출처. <b>정격의 크기 상한은 일부러 두지 않는다.</b> 실데이터가 없는 지금 상한은
 * 추측이라 정당한 장비를 막을 수 있고, 값이 이상하면 노드 응답과 원장 목록에 그대로 드러난다.</p>
 *
 * <p>노드 식별자 중복은 DB 유니크 제약이 잡지만 그때는 500 으로 새므로 먼저 조회해 409 를 낸다.</p>
 */
@Service
public class NodePowerSpecService {

    private final NodePowerSpecRepository repository;
    private final ScheduleActorResolver actorResolver;

    public NodePowerSpecService(NodePowerSpecRepository repository, ScheduleActorResolver actorResolver) {
        this.repository = repository;
        this.actorResolver = actorResolver;
    }

    // ── 조회 ───────────────────────────────────────────────────

    /** 목록. {@code nodeId} 를 주면 그 문자열을 포함하는 노드만(부분 일치, 대소문자 무시). */
    @Transactional(readOnly = true)
    public List<NodePowerSpec> list(String nodeId) {
        List<NodePowerSpec> all = repository.findAllByOrderByNodeIdAsc();
        if (nodeId == null || nodeId.isBlank()) return all;
        String needle = nodeId.trim().toLowerCase();
        return all.stream().filter(s -> s.getNodeId().toLowerCase().contains(needle)).toList();
    }

    /** 수집·추세가 쓰는 스냅샷 — 호출 1회당 한 번만 읽는다. */
    @Transactional(readOnly = true)
    public NodePowerSpecs snapshot() {
        return NodePowerSpecs.of(repository.findAll());
    }

    /** 없으면 404. */
    @Transactional(readOnly = true)
    public NodePowerSpec require(Long id) {
        return repository.findById(id)
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND, "노드 전력 사양을 찾을 수 없습니다: " + id));
    }

    // ── 변경 ───────────────────────────────────────────────────

    @Transactional
    public NodePowerSpec create(NodePowerSpecRequest r) {
        if (r == null) throw badRequest("요청 본문이 필요합니다");
        String nodeId = requireText(r.nodeId(), "노드 식별자(nodeId)", NodePowerSpec.NODE_ID_MAX);
        if (r.capacityWatts() == null) throw badRequest("정격 전력(capacityWatts)은 필수입니다");
        repository.findByNodeId(nodeId).ifPresent(existing -> { throw conflict(existing); });

        NodePowerSpec spec = new NodePowerSpec(nodeId, capacity(r.capacityWatts()));
        apply(spec, r);
        ScheduleActorResolver.Actor actor = actorResolver.current();
        spec.setCreatedBy(actor.email(), actor.name());
        spec.setUpdatedBy(actor.email(), actor.name());
        return repository.save(spec);
    }

    /** 부분 수정 — 보낸 키만 반영한다. 노드 식별자를 바꾸면 중복 검사를 다시 한다. */
    @Transactional
    public NodePowerSpec update(Long id, NodePowerSpecRequest r) {
        if (r == null) throw badRequest("요청 본문이 필요합니다");
        NodePowerSpec spec = require(id);

        if (r.nodeId() != null) {
            String nodeId = requireText(r.nodeId(), "노드 식별자(nodeId)", NodePowerSpec.NODE_ID_MAX);
            if (!nodeId.equals(spec.getNodeId())) {
                repository.findByNodeId(nodeId).ifPresent(existing -> { throw conflict(existing); });
                spec.setNodeId(nodeId);
            }
        }
        if (r.capacityWatts() != null) spec.setCapacityWatts(capacity(r.capacityWatts()));
        apply(spec, r);
        ScheduleActorResolver.Actor actor = actorResolver.current();
        spec.setUpdatedBy(actor.email(), actor.name());
        return repository.save(spec);
    }

    /** 삭제. 없으면 404. 참조하는 것이 없어 다른 규칙은 없다 — 다음 수집부터 기본값으로 돌아간다. */
    @Transactional
    public void delete(Long id) {
        repository.delete(require(id));
    }

    // ── 반영·검증 ──────────────────────────────────────────────

    private static void apply(NodePowerSpec spec, NodePowerSpecRequest r) {
        if (r.source() != null) {
            spec.setSource(EnumSupport.parse(NodePowerSpec.PowerSpecSource.class, r.source(), "전력 사양 출처"));
        }
        if (r.description() != null) {
            JsonNode node = r.description();
            if (node.isNull()) {
                spec.setDescription(null);
            } else {
                if (!node.isString()) throw badRequest("설명은 문자열이어야 합니다");
                spec.setDescription(limit(node.asString(), "설명", NodePowerSpec.DESCRIPTION_MAX));
            }
        }
    }

    /**
     * 정격 검증 — 0 보다 크고 유한해야 한다. <b>크기 상한은 없다.</b>
     *
     * <p>무한대만 막는 이유: 여유율이 {@code (∞ − 실측) / ∞ = NaN} 이 되어 후보 정렬이 깨지고 응답 직렬화도
     * 표준을 벗어난다. 큰 유한값은 그 노드의 여유율이 1 에 가까워질 뿐 계산이 깨지지 않고, 값 자체가 노드 응답과
     * 원장 목록에 그대로 보인다.</p>
     */
    private static double capacity(double v) {
        if (!Double.isFinite(v) || v <= 0) {
            throw badRequest("정격 전력(capacityWatts)은 0 보다 큰 유한한 값이어야 합니다: " + v);
        }
        return v;
    }

    private static String requireText(String value, String label, int max) {
        if (value == null || value.isBlank()) throw badRequest(label + "은(는) 필수입니다");
        return limit(value.trim(), label, max);
    }

    private static String limit(String value, String label, int max) {
        if (value.length() > max) {
            throw badRequest(String.format("%s은(는) %d자 이내여야 합니다 (현재 %d자)", label, max, value.length()));
        }
        return value;
    }

    private static BusinessException conflict(NodePowerSpec existing) {
        return new BusinessException(HttpStatus.CONFLICT, String.format(
                "이미 등록된 노드입니다: %s (id %d, 정격 %.1fW). 수정하려면 PUT /node-power-specs/%d 를 쓰세요",
                existing.getNodeId(), existing.getId(), existing.getCapacityWatts(), existing.getId()));
    }

    private static BusinessException badRequest(String message) {
        return new BusinessException(HttpStatus.BAD_REQUEST, message);
    }
}
