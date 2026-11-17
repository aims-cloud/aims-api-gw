package com.innogrid.aims.scheduler.controller;

import java.util.List;

import org.springframework.web.bind.annotation.*;

import com.innogrid.aims.common.dto.ApiResponse;
import com.innogrid.aims.scheduler.dto.MdcLinkMatrixResponse;
import com.innogrid.aims.scheduler.dto.MdcLinkRequest;
import com.innogrid.aims.scheduler.dto.MdcLinkResponse;
import com.innogrid.aims.scheduler.service.MdcLinkService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * MDC 링크 원장 API. 요청은 {@link MdcLinkRequest}, 응답은 {@link MdcLinkResponse}·{@link MdcLinkMatrixResponse}.
 * 검증·저장 규칙은 {@link MdcLinkService}에 있다.
 *
 * <p>{@code /matrix}는 {@code /{linkId}}보다 리터럴 경로라 먼저 매칭된다 — {@code MdcLinkApiContractTest}가 고정.</p>
 */
@Tag(name = "MDC 링크", description = "MDC 간 연결 품질(지연·대역폭·손실·상태) 원장. 정책 affinityEnabled + 워크로드 dataMdcId 가 있을 때 "
        + "DOWN 링크 제외는 모든 엔진에 공통 적용되고, 링크 품질 점수는 weighted 엔진에서만 반영됩니다. 이 점수에는 워크로드 요구 대역폭과 링크 대역폭이 모두 있을 때 모자란 비율(0~1)만큼 링크 비용이 더해집니다")
@RestController
@RequestMapping("/api/v1/scheduler/mdc-links")
public class MdcLinkController {

    private final MdcLinkService service;

    public MdcLinkController(MdcLinkService service) {
        this.service = service;
    }

    @Operation(summary = "링크 목록",
               description = "(mdcA, mdcB) 사전순. mdcId 를 주면 어느 편이든 그 MDC 가 걸린 링크만, status 를 주면 그 상태만.")
    @GetMapping
    public ApiResponse<List<MdcLinkResponse>> list(
            @Parameter(description = "어느 편이든 이 MDC 가 걸린 링크") @RequestParam(required = false) String mdcId,
            @Parameter(description = "UP | DEGRADED | DOWN (대소문자 무시)") @RequestParam(required = false) String status) {
        return ApiResponse.ok(service.list(mdcId, status).stream().map(MdcLinkResponse::from).toList());
    }

    @Operation(summary = "링크 매트릭스",
               description = "등록된 링크에 나오는 MDC 를 사전순으로 모아 대칭 행렬로 돌려줍니다. links[i][j] 는 mdcIds[i]–mdcIds[j] 링크, "
                       + "없으면 null, 대각은 항상 null. 링크가 없으면 둘 다 빈 배열.")
    @GetMapping("/matrix")
    public ApiResponse<MdcLinkMatrixResponse> matrix() {
        return ApiResponse.ok(service.matrix());
    }

    @Operation(summary = "링크 상세")
    @GetMapping("/{linkId}")
    public ApiResponse<MdcLinkResponse> get(@PathVariable Long linkId) {
        return ApiResponse.ok(MdcLinkResponse.from(service.require(linkId)));
    }

    @Operation(summary = "링크 생성",
               description = "mdcA·mdcB(서로 다름, 순서 무관 — 사전순으로 저장)·latencyMs(0 이상) 필수. bandwidthMbps 는 0 초과이며 "
                       + "워크로드 requiredBandwidthMbps 가 있는 배치에서 부족분만큼 링크 점수를 깎습니다. "
                       + "packetLossPercent 는 0~100. status 기본 UP, source 기본 MANUAL. 같은 쌍(역방향 포함)은 409.")
    @PostMapping
    public ApiResponse<MdcLinkResponse> create(@RequestBody MdcLinkRequest request) {
        return ApiResponse.ok(MdcLinkResponse.from(service.create(request)));
    }

    @Operation(summary = "링크 수정",
               description = "부분 수정 — 요청에 있는 키만 반영합니다. 쌍을 바꾸려면 mdcA 와 mdcB 를 함께 주어야 합니다. "
                       + "bandwidthMbps·packetLossPercent·description 에 null 을 주면 비웁니다.")
    @PutMapping("/{linkId}")
    public ApiResponse<MdcLinkResponse> update(@PathVariable Long linkId, @RequestBody MdcLinkRequest request) {
        return ApiResponse.ok(MdcLinkResponse.from(service.update(linkId, request)));
    }

    @Operation(summary = "링크 삭제", description = "없으면 404.")
    @DeleteMapping("/{linkId}")
    public ApiResponse<Void> delete(@PathVariable Long linkId) {
        service.delete(linkId);
        return ApiResponse.ok(null);
    }
}
