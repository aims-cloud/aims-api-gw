package com.innogrid.aims.scheduler.service;

import java.util.List;
import java.util.Optional;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.innogrid.aims.common.exception.BusinessException;
import com.innogrid.aims.scheduler.dto.MdcLinkMatrixResponse;
import com.innogrid.aims.scheduler.dto.MdcLinkRequest;
import com.innogrid.aims.scheduler.entity.MdcLink;
import com.innogrid.aims.scheduler.repository.MdcLinkRepository;

import tools.jackson.databind.JsonNode;

/**
 * MDC 링크 원장 — 검증·저장 규칙.
 *
 * <p>거부하는 값은 "저장하면 확실히 틀린 것"이다 — 같은 MDC 사이의 링크(의미 없음), 음수 지연·0 이하 대역폭·범위 밖
 * 손실률(비용 함수가 뒤집힘), 모르는 상태·출처. MDC 식별자의 <b>존재</b>는 검증하지 않는다 — 그러려면 원장이
 * 메트릭 저장소를 불러야 한다.</p>
 *
 * <p>쌍 중복은 DB 유니크 제약이 잡아 주지만 그때는 500 으로 새므로 먼저 조회해 409 를 낸다. 조회는 반드시
 * {@link MdcLink.Pair#of} 로 정렬한 뒤 한다 — 역방향 등록이 여기서 잡힌다.</p>
 */
@Service
public class MdcLinkService {

    static final int MDC_ID_MAX = 50;
    static final int DESCRIPTION_MAX = 300;

    private final MdcLinkRepository repository;
    private final ScheduleActorResolver actorResolver;

    public MdcLinkService(MdcLinkRepository repository, ScheduleActorResolver actorResolver) {
        this.repository = repository;
        this.actorResolver = actorResolver;
    }

    // ── 조회 ───────────────────────────────────────────────────

    /** 목록. {@code mdcId} 는 어느 편이든 걸린 링크, {@code status} 는 상태 필터. 둘 다 선택. */
    public List<MdcLink> list(String mdcId, String status) {
        MdcLink.LinkStatus wanted = status == null || status.isBlank() ? null
                : EnumSupport.parse(MdcLink.LinkStatus.class, status, "링크 상태");
        List<MdcLink> base = mdcId == null || mdcId.isBlank()
                ? repository.findAllByOrderByMdcAAscMdcBAsc()
                : repository.findByMdcAOrMdcBOrderByMdcAAscMdcBAsc(mdcId.trim(), mdcId.trim());
        return wanted == null ? base : base.stream().filter(l -> l.getStatus() == wanted).toList();
    }

    public MdcLinkMatrixResponse matrix() {
        return MdcLinkMatrixResponse.of(repository.findAllByOrderByMdcAAscMdcBAsc());
    }

    /** 없으면 404. */
    public MdcLink require(Long id) {
        return repository.findById(id)
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND, "MDC 링크를 찾을 수 없습니다: " + id));
    }

    // ── 변경 ───────────────────────────────────────────────────

    @Transactional
    public MdcLink create(MdcLinkRequest r) {
        if (r == null) throw badRequest("요청 본문이 필요합니다");

        MdcLink.Pair pair = pairOf(r.mdcA(), r.mdcB());
        if (r.latencyMs() == null) throw badRequest("지연(latencyMs)은 필수입니다");
        if (repository.findByMdcAAndMdcB(pair.a(), pair.b()).isPresent()) {
            throw conflict(pair);
        }

        MdcLink link = new MdcLink(pair.a(), pair.b(), latency(r.latencyMs()));
        apply(link, r);

        ScheduleActorResolver.Actor actor = actorResolver.current();
        link.setCreatedBy(actor.email(), actor.name());
        link.setUpdatedBy(actor.email(), actor.name());
        return repository.save(link);
    }

    /** 부분 수정. 요청에 있는 키만 반영한다. 쌍은 {@code mdcA}·{@code mdcB} 를 함께 줘야 바꾼다. */
    @Transactional
    public MdcLink update(Long id, MdcLinkRequest r) {
        MdcLink link = require(id);
        if (r == null) throw badRequest("요청 본문이 필요합니다");

        if (r.mdcA() != null || r.mdcB() != null) {
            if (r.mdcA() == null || r.mdcB() == null) {
                throw badRequest("쌍을 바꾸려면 mdcA 와 mdcB 를 함께 주어야 합니다");
            }
            MdcLink.Pair pair = pairOf(r.mdcA(), r.mdcB());
            if (!pair.equals(link.pair())) {
                Optional<MdcLink> other = repository.findByMdcAAndMdcB(pair.a(), pair.b());
                if (other.isPresent() && !other.get().getId().equals(link.getId())) throw conflict(pair);
                link.repair(pair.a(), pair.b());
            }
        }
        if (r.latencyMs() != null) link.setLatencyMs(latency(r.latencyMs()));
        apply(link, r);

        ScheduleActorResolver.Actor actor = actorResolver.current();
        link.setUpdatedBy(actor.email(), actor.name());
        return repository.save(link);
    }

    /** 삭제. 없으면 404. 참조하는 것이 없어 다른 규칙은 없다. */
    @Transactional
    public void delete(Long id) {
        repository.delete(require(id));
    }

    // ── 반영 — 생성·수정 공통 (키 없음은 유지, 명시적 null 은 비움) ──

    private void apply(MdcLink link, MdcLinkRequest r) {
        if (r.bandwidthMbps() != null) {
            Double v = number(r.bandwidthMbps(), "대역폭(bandwidthMbps)");
            if (v != null && v <= 0) throw badRequest("대역폭은 0 보다 커야 합니다: " + v);
            link.setBandwidthMbps(v);
        }
        if (r.packetLossPercent() != null) {
            Double v = number(r.packetLossPercent(), "손실률(packetLossPercent)");
            if (v != null && (v < 0 || v > 100)) throw badRequest("손실률은 0 이상 100 이하여야 합니다: " + v);
            link.setPacketLossPercent(v);
        }
        if (r.description() != null) {
            if (r.description().isNull()) {
                link.setDescription(null);
            } else {
                if (!r.description().isString()) throw badRequest("설명은 문자열이어야 합니다");
                link.setDescription(limit(r.description().asString(), "설명", DESCRIPTION_MAX));
            }
        }
        if (r.status() != null) link.setStatus(EnumSupport.parse(MdcLink.LinkStatus.class, r.status(), "링크 상태"));
        if (r.source() != null) link.setSource(EnumSupport.parse(MdcLink.LinkSource.class, r.source(), "링크 출처"));
    }

    // ── 검증 헬퍼 ───────────────────────────────────────────────

    private static MdcLink.Pair pairOf(String a, String b) {
        String x = requireText(a, "mdcA", MDC_ID_MAX);
        String y = requireText(b, "mdcB", MDC_ID_MAX);
        if (x.equals(y)) throw badRequest("같은 MDC 사이에는 링크를 둘 수 없습니다: " + x);
        return MdcLink.Pair.of(x, y);
    }

    private static double latency(double v) {
        if (v < 0 || Double.isNaN(v)) throw badRequest("지연(latencyMs)은 0 이상이어야 합니다: " + v);
        return v;
    }

    /** 명시적 null 은 null(모름), 숫자는 값, 그 외는 400. */
    private static Double number(JsonNode node, String label) {
        if (node.isNull()) return null;
        if (!node.isNumber()) throw badRequest(label + "은(는) 숫자여야 합니다");
        return node.asDouble();
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

    private static BusinessException conflict(MdcLink.Pair pair) {
        return new BusinessException(HttpStatus.CONFLICT,
                String.format("이미 등록된 MDC 쌍입니다: %s ↔ %s (역방향 포함)", pair.a(), pair.b()));
    }

    private static BusinessException badRequest(String message) {
        return new BusinessException(HttpStatus.BAD_REQUEST, message);
    }
}
