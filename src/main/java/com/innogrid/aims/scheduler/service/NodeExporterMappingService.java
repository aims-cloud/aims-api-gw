package com.innogrid.aims.scheduler.service;

import java.util.List;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.innogrid.aims.common.exception.BusinessException;
import com.innogrid.aims.scheduler.dto.NodeExporterMappingRequest;
import com.innogrid.aims.scheduler.entity.NodeExporterMapping;
import com.innogrid.aims.scheduler.model.ExporterInstanceCandidates;
import com.innogrid.aims.scheduler.repository.NodeExporterMappingRepository;

import tools.jackson.databind.JsonNode;

/**
 * 익스포터 대응 원장 — 검증·저장 규칙.
 *
 * <p>거부하는 값은 "저장하면 확실히 틀린 것"이다(다른 원장과 같은 원칙) — 빈 노드 식별자, 길이 초과, 노드 식별자와
 * 같은 값을 익스포터 칸에 넣는 것. 마지막은 뜻이 없어서다: 그대로 두면 해석기가 알아서 그대로 쓴다. instance의
 * <b>존재는 검증하지 않는다</b> — 검증하려면 원장 CRUD가 저장소를 불러야 한다. 어느 시리즈에도 걸리지 않는 오타는
 * 그 노드의 GPU·전력·온도가 미계측으로 남을 뿐 다른 노드를 건드리지 않는다.</p>
 *
 * <p>노드 식별자 중복은 DB 유니크 제약이 잡지만 그때는 500으로 새므로 먼저 조회해 409를 낸다. 대소문자만 다른
 * 식별자도 409다 — 라벨은 대소문자를 가리므로 철자가 다른 행은 영원히 쓰이지 않는데 운영자는 등록했다고 믿게 된다.</p>
 *
 * <p><b>한 익스포터 instance는 한 노드에만.</b> 다른 행이 이미 쓰는 DCGM·IPMI instance는 409다. 같은 시리즈를 두
 * 노드에 넣으면 그 값이 두 번 세어져 클러스터 전력 합·에너지·GPU 총량이 부푼다. 수집기도 같은 충돌을 미계측으로
 * 떨어뜨리지만, 여기서 막아야 운영자가 그 자리에서 안다. 중앙 IPMI 익스포터가 노드를 instance로 구분하지 못하면
 * 그 노드들은 미계측으로 두는 것이 맞다.</p>
 */
@Service
public class NodeExporterMappingService {

    private final NodeExporterMappingRepository repository;
    private final ScheduleActorResolver actorResolver;
    private final ExporterInstanceResolver resolver;

    public NodeExporterMappingService(NodeExporterMappingRepository repository, ScheduleActorResolver actorResolver,
                                      ExporterInstanceResolver resolver) {
        this.repository = repository;
        this.actorResolver = actorResolver;
        this.resolver = resolver;
    }

    /**
     * 화면이 고를 후보 instance — 저장소에 지금 있는 DCGM·IPMI 시리즈와 수집기의 배정. 원장은 해석기가 스스로 읽으므로
     * 이 메서드는 트랜잭션을 열지 않는다(발견 질의가 저장소를 기다리는 동안 DB 연결을 붙잡지 않기 위해).
     */
    public ExporterInstanceCandidates candidates() {
        return resolver.candidates();
    }

    // ── 조회 ───────────────────────────────────────────────────

    /** 목록. {@code nodeId}를 주면 그 문자열을 포함하는 노드만(부분 일치, 대소문자 무시). */
    @Transactional(readOnly = true)
    public List<NodeExporterMapping> list(String nodeId) {
        List<NodeExporterMapping> all = repository.findAllByOrderByNodeIdAsc();
        if (nodeId == null || nodeId.isBlank()) return all;
        String needle = nodeId.trim().toLowerCase();
        return all.stream().filter(m -> m.getNodeId().toLowerCase().contains(needle)).toList();
    }

    /** 없으면 404. */
    @Transactional(readOnly = true)
    public NodeExporterMapping require(Long id) {
        return repository.findById(id)
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND, "익스포터 대응을 찾을 수 없습니다: " + id));
    }

    // ── 변경 ───────────────────────────────────────────────────

    @Transactional
    public NodeExporterMapping create(NodeExporterMappingRequest r) {
        if (r == null) throw badRequest("요청 본문이 필요합니다");
        String nodeId = requireText(r.nodeId(), "노드 식별자(nodeId)", NodeExporterMapping.INSTANCE_MAX);
        repository.findByNodeIdIgnoreCase(nodeId).ifPresent(existing -> { throw conflict(existing); });

        NodeExporterMapping mapping = new NodeExporterMapping(nodeId);
        apply(mapping, r);
        rejectSharedInstances(mapping);
        ScheduleActorResolver.Actor actor = actorResolver.current();
        mapping.setCreatedBy(actor.email(), actor.name());
        mapping.setUpdatedBy(actor.email(), actor.name());
        return saveOrConflict(mapping);
    }

    /** 부분 수정 — 보낸 키만 반영한다. 노드 식별자를 바꾸면 중복 검사를 다시 한다. */
    @Transactional
    public NodeExporterMapping update(Long id, NodeExporterMappingRequest r) {
        if (r == null) throw badRequest("요청 본문이 필요합니다");
        NodeExporterMapping mapping = require(id);

        if (r.nodeId() != null) {
            String nodeId = requireText(r.nodeId(), "노드 식별자(nodeId)", NodeExporterMapping.INSTANCE_MAX);
            if (!nodeId.equals(mapping.getNodeId())) {
                repository.findByNodeIdIgnoreCase(nodeId)
                        .filter(existing -> !existing.getId().equals(mapping.getId()))
                        .ifPresent(existing -> { throw conflict(existing); });
                mapping.setNodeId(nodeId);
            }
        }
        apply(mapping, r);
        // 노드 식별자를 바꿔 저장된 익스포터 칸과 같아졌으면 그 칸은 뜻이 없다 — 비워 그대로로 떨어뜨린다
        if (mapping.getNodeId().equals(mapping.getDcgmInstance())) mapping.setDcgmInstance(null);
        if (mapping.getNodeId().equals(mapping.getIpmiInstance())) mapping.setIpmiInstance(null);
        rejectSharedInstances(mapping);
        ScheduleActorResolver.Actor actor = actorResolver.current();
        mapping.setUpdatedBy(actor.email(), actor.name());
        return saveOrConflict(mapping);
    }

    /** 삭제. 없으면 404. 지우면 다음 수집부터 호스트명 일치와 그대로로 돌아간다. */
    @Transactional
    public void delete(Long id) {
        repository.delete(require(id));
    }

    /** 저장하며 동시 등록 경합을 409로 바꾼다 — 다른 원장과 같은 처리. */
    NodeExporterMapping saveOrConflict(NodeExporterMapping mapping) {
        try {
            return repository.saveAndFlush(mapping);
        } catch (DataIntegrityViolationException race) {
            throw new BusinessException(HttpStatus.CONFLICT, String.format(
                    "이미 등록된 노드입니다: %s (동시에 등록·수정되어 유니크 제약에 걸렸습니다)", mapping.getNodeId()));
        }
    }

    // ── 반영·검증 ──────────────────────────────────────────────

    /** 다른 행이 이미 쓰는 DCGM·IPMI instance면 409. 원장은 작아 전건을 훑는다. */
    private void rejectSharedInstances(NodeExporterMapping mapping) {
        for (NodeExporterMapping other : repository.findAll()) {
            if (other.getId() != null && other.getId().equals(mapping.getId())) continue;
            if (mapping.getDcgmInstance() != null && mapping.getDcgmInstance().equals(other.getDcgmInstance())) {
                throw shared("DCGM instance", mapping.getDcgmInstance(), other);
            }
            if (mapping.getIpmiInstance() != null && mapping.getIpmiInstance().equals(other.getIpmiInstance())) {
                throw shared("IPMI instance", mapping.getIpmiInstance(), other);
            }
        }
    }

    private static BusinessException shared(String label, String value, NodeExporterMapping other) {
        return new BusinessException(HttpStatus.CONFLICT, String.format(
                "%s %s 은(는) 이미 노드 %s (id %d) 에 적혀 있습니다. 한 시리즈를 두 노드에 넣으면 값이 두 번 세어져 합계가 부풉니다 — "
                        + "익스포터가 노드를 instance 로 구분하지 못하면 그 노드들은 비워 두십시오",
                label, value, other.getNodeId(), other.getId()));
    }

    /**
     * 두 익스포터 칸은 {@link JsonNode}로 받아 "비우는 것"을 표현한다 — 필드가 {@code null}이면 키 없음(유지),
     * {@code isNull()}이면 명시적 null(비움 → 호스트명 일치로 대체), 아니면 값.
     */
    private void apply(NodeExporterMapping mapping, NodeExporterMappingRequest r) {
        if (r.dcgmInstance() != null) mapping.setDcgmInstance(instanceOf(r.dcgmInstance(), "DCGM instance", mapping.getNodeId()));
        if (r.ipmiInstance() != null) mapping.setIpmiInstance(instanceOf(r.ipmiInstance(), "IPMI instance", mapping.getNodeId()));
        if (r.description() != null) {
            JsonNode node = r.description();
            if (node.isNull()) {
                mapping.setDescription(null);
            } else {
                if (!node.isString()) throw badRequest("설명은 문자열이어야 합니다");
                mapping.setDescription(limit(node.asString(), "설명", NodeExporterMapping.DESCRIPTION_MAX));
            }
        }
    }

    /** 익스포터 instance 칸 — null로 비울 수 있다. 노드 식별자와 같은 값은 뜻이 없어 거부한다. */
    private static String instanceOf(JsonNode node, String label, String nodeId) {
        if (node.isNull()) return null;
        if (!node.isString()) throw badRequest(label + "은(는) 문자열이어야 합니다");
        String value = node.asString().trim();
        if (value.isEmpty()) return null;
        if (value.equals(nodeId)) {
            throw badRequest(String.format("%s이(가) 노드 식별자와 같습니다 — 같으면 원장이 필요 없습니다. 비워 두면 그대로 씁니다", label));
        }
        return limit(value, label, NodeExporterMapping.INSTANCE_MAX);
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

    private static BusinessException conflict(NodeExporterMapping existing) {
        return new BusinessException(HttpStatus.CONFLICT, String.format(
                "이미 등록된 노드입니다: %s (id %d). 수정하려면 PUT /node-exporter-mappings/%d 를 쓰세요",
                existing.getNodeId(), existing.getId(), existing.getId()));
    }

    private static BusinessException badRequest(String message) {
        return new BusinessException(HttpStatus.BAD_REQUEST, message);
    }
}
