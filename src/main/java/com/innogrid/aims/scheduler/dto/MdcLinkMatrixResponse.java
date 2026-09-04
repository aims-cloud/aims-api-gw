package com.innogrid.aims.scheduler.dto;

import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;

import com.innogrid.aims.scheduler.entity.MdcLink;

/**
 * MDC 링크 매트릭스 — 토폴로지 화면이 표 하나로 그릴 수 있는 형태.
 *
 * <p>{@code mdcIds} 는 <b>등록된 링크에 나오는 MDC 만</b> 사전순으로 모은 것이다(노드 라벨을 읽지 않는다 —
 * 원장은 메트릭 저장소와 독립이어야 한다). {@code links[i][j]} 는 {@code mdcIds[i]}–{@code mdcIds[j]} 링크이며
 * 없으면 null, 대각은 항상 null, {@code links[i][j] == links[j][i]} 로 대칭이다.</p>
 *
 * <p>링크가 하나도 없으면 {@code mdcIds} 와 {@code links} 모두 빈 배열이다.</p>
 *
 * @param mdcIds 사전순 MDC 식별자
 * @param links  {@code mdcIds.size()} × {@code mdcIds.size()} 대칭 행렬(칸은 nullable)
 */
public record MdcLinkMatrixResponse(List<String> mdcIds, List<List<MdcLinkResponse>> links) {

    public static MdcLinkMatrixResponse of(List<MdcLink> all) {
        TreeSet<String> ids = new TreeSet<>();
        for (MdcLink l : all) {
            ids.add(l.getMdcA());
            ids.add(l.getMdcB());
        }
        List<String> mdcIds = List.copyOf(ids);

        int n = mdcIds.size();
        List<List<MdcLinkResponse>> matrix = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            List<MdcLinkResponse> row = new ArrayList<>(n);
            for (int j = 0; j < n; j++) row.add(null);
            matrix.add(row);
        }
        for (MdcLink l : all) {
            int i = mdcIds.indexOf(l.getMdcA());
            int j = mdcIds.indexOf(l.getMdcB());
            MdcLinkResponse cell = MdcLinkResponse.from(l);
            matrix.get(i).set(j, cell);
            matrix.get(j).set(i, cell);
        }
        return new MdcLinkMatrixResponse(mdcIds, matrix);
    }
}
