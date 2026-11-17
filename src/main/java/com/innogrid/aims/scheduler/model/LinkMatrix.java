package com.innogrid.aims.scheduler.model;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.innogrid.aims.scheduler.entity.MdcLink;

/**
 * 배치 한 번에 쓰는 MDC 링크 원장의 <b>불변 스냅샷</b>.
 *
 * <p>실행당 한 번 원장을 읽어 만들고({@code SchedulingService.createContext}), 엔진과 제약 평가기는
 * 이것만 본다 — 노드 메트릭을 실행당 1회 수집하는 원칙과 같다. 배치 도중 원장이 바뀌어도
 * 같은 실행 안에서는 같은 링크 품질을 본다.</p>
 *
 * <p>키는 {@link MdcLink.Pair}로 정렬된 쌍이라 방향이 없다. 같은 MDC·미등록 쌍은 {@link #between}이
 * 비어 있다 — 행렬은 둘을 구분하지 않으므로 <b>같은 MDC 판정은 호출자가 행렬을 보기 전에</b> 한다.
 * 제약(C5)은 빈 값을 통과시키고, 점수({@code NetworkModel})는 같은 MDC 1.0 · 미등록 원격은 설정값(기본 0.5)을
 * 준다 — 모르는 값으로 배치를 막지는 않되, 모르는 경로가 측정된 양호 경로를 이기지도 않게.</p>
 */
public final class LinkMatrix {

    /** 링크가 하나도 없는 매트릭스. 게이트가 꺼진 컨텍스트의 기본값이다. */
    public static final LinkMatrix EMPTY = new LinkMatrix(Collections.emptyMap());

    /**
     * 한 링크의 품질 — 엔진이 필요로 하는 네 값만 담는다(엔티티를 엔진에 노출하지 않는다).
     *
     * <p>인자 순서는 원장 컬럼 순서(지연 → 대역폭 → 손실 → 상태)와 같다.</p>
     *
     * @param latencyMs         왕복 지연(ms)
     * @param bandwidthMbps     대역폭(Mbps). 모르면 null → 부족분 항이 0이라 예전과 같은 비용이다
     * @param packetLossPercent 손실률(%). 모르면 null → 0으로 본다
     * @param status            UP · DEGRADED · DOWN
     */
    public record Quality(double latencyMs, Double bandwidthMbps, Double packetLossPercent, MdcLink.LinkStatus status) {
        public double lossOrZero() {
            return packetLossPercent == null ? 0.0 : packetLossPercent;
        }
        public boolean isDown() {
            return status == MdcLink.LinkStatus.DOWN;
        }
    }

    private final Map<MdcLink.Pair, Quality> links;

    private LinkMatrix(Map<MdcLink.Pair, Quality> links) {
        this.links = links;
    }

    /** 원장 행들로 만든다. 저장된 쌍은 이미 정렬되어 있지만 안전하게 다시 정렬한다. */
    public static LinkMatrix of(List<MdcLink> rows) {
        if (rows == null || rows.isEmpty()) return EMPTY;
        Map<MdcLink.Pair, Quality> m = new HashMap<>();
        for (MdcLink l : rows) {
            m.put(MdcLink.Pair.of(l.getMdcA(), l.getMdcB()),
                  new Quality(l.getLatencyMs(), l.getBandwidthMbps(), l.getPacketLossPercent(), l.getStatus()));
        }
        return new LinkMatrix(Collections.unmodifiableMap(m));
    }

    /**
     * 두 MDC 사이 링크. 같은 MDC·null·미등록이면 비어 있다 — 셋을 구분하지 않으므로 해석은 호출자 몫이다.
     * 노드 MDC가 {@code unknown}이라도 특별 취급하지 않는다 — 원장에 없으면 그냥 비어 있다.
     */
    public Optional<Quality> between(String mdcX, String mdcY) {
        if (mdcX == null || mdcY == null || mdcX.equals(mdcY)) return Optional.empty();
        return Optional.ofNullable(links.get(MdcLink.Pair.of(mdcX, mdcY)));
    }

    public boolean isEmpty() {
        return links.isEmpty();
    }

    /** 원장 스냅샷 전부(바꿀 수 없는 맵) — 실행 입력 저장이 읽는다. */
    public Map<MdcLink.Pair, Quality> entries() {
        return links;
    }

    public int size() {
        return links.size();
    }
}
