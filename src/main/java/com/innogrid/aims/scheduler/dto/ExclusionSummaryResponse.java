package com.innogrid.aims.scheduler.dto;

import java.time.OffsetDateTime;
import java.util.SortedMap;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 배치 이력의 제외 사유 코드 합계 — 창·필터 안 결정들의 {@code exclusionCodes}를 서버가 더한 결과.
 *
 * <p>{@code codes}의 키는 등장한 코드만(이름 오름차순, 문자열이라 옛·새 코드가 보존된다). 값의 {@code decisions}는 그 코드가
 * 든 결정 수, {@code candidates}는 그 코드로 빠진 후보 수의 합이다. 같은 필터로 {@code /history/page?exclusionCode=CODE}를
 * 응답의 {@code from}·{@code to}를 그대로 넘겨 부르면 {@code totalElements}가 {@code codes[CODE].decisions}와 같다(두 API가 같은
 * 조건식을 쓴다) — 단 {@code skipped}가 0일 때다. 형식은 깨졌지만 코드 토큰은 든 행은 페이징의 LIKE에는 걸리고 여기서는 빠진다.</p>
 */
@Schema(description = "배치 이력의 제외 사유 코드 합계 — 창·필터 안 결정의 집계를 서버가 더한 결과")
public record ExclusionSummaryResponse(
        @Schema(description = "집계 창 시작(포함). from·to 를 둘 다 비우면 기본 창(최근 30일)의 시작, to 만 주면 null(하한 없음)")
        OffsetDateTime from,
        @Schema(description = "집계 창 끝(미포함). from·to 를 둘 다 비우면 지금, from 만 주면 null(상한 없음)")
        OffsetDateTime to,
        @Schema(description = "창·필터 안 결정 수 — 집계가 없는 결정도 센다")
        long decisions,
        @Schema(description = "제외 사유가 하나 이상 정상으로 읽힌 결정 수 — skipped 는 들지 않는다. decisions − withExclusions − skipped 가 제외 없는 결정(옛 행 포함) 수")
        long withExclusions,
        @Schema(description = "집계 문자열 형식이 어긋나 건너뛴 결정 수 — 정상이면 0")
        long skipped,
        @Schema(description = "코드별 합계 — 등장한 코드만 코드 이름 오름차순, 없으면 빈 객체. 값의 decisions 는 그 코드가 든 결정 수, candidates 는 빠진 후보 수의 합")
        SortedMap<String, CodeCount> codes) {

    @Schema(description = "코드 하나의 합계")
    public record CodeCount(
            @Schema(description = "그 코드가 든 결정 수 — 한 결정에 코드가 여럿이면 코드마다 세므로 코드별 합은 withExclusions 보다 클 수 있다(비율에 쓰지 말 것)")
            long decisions,
            @Schema(description = "그 코드로 빠진 후보 수의 합 — 한 노드는 먼저 걸린 제약 하나로만 세므로 코드끼리 겹치지 않는다(비율은 이 값으로)")
            long candidates) {
    }
}
