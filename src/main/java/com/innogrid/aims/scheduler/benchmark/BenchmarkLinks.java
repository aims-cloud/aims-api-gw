package com.innogrid.aims.scheduler.benchmark;

import java.util.ArrayList;
import java.util.List;

import com.innogrid.aims.scheduler.entity.MdcLink;
import com.innogrid.aims.scheduler.metrics.SyntheticCluster;
import com.innogrid.aims.scheduler.model.LinkMatrix;

/**
 * 판 파일의 {@code links:} 절을 배치 컨텍스트의 {@link LinkMatrix}로 — 판 파일이 원장 노릇을 한다.
 *
 * <p>비영속 {@link MdcLink}를 만들어 기존 {@link LinkMatrix#of}에 넘긴다 — 모델 API를 넓히지 않고, 정책 사본({@code BenchmarkPolicies})·
 * detached 워크로드({@code BenchmarkWorkloads})와 같은 방식이다. 저장소를 주입받지 않으므로 저장될 경로가 없다.
 * 링크 없는 판은 {@link LinkMatrix#EMPTY}를 돌려준다.</p>
 */
public final class BenchmarkLinks {

    private BenchmarkLinks() {
    }

    public static LinkMatrix matrixOf(SyntheticCluster cluster) {
        if (cluster.links().isEmpty()) return LinkMatrix.EMPTY;
        List<MdcLink> rows = new ArrayList<>(cluster.links().size());
        for (SyntheticCluster.Link l : cluster.links()) {
            MdcLink row = new MdcLink(l.mdcA(), l.mdcB(), l.latencyMs());
            row.setBandwidthMbps(l.bandwidthMbps());
            row.setPacketLossPercent(l.packetLossPercent());
            row.setStatus(l.status());
            rows.add(row);
        }
        return LinkMatrix.of(rows);
    }
}
