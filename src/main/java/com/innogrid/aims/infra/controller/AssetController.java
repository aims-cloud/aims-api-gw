package com.innogrid.aims.infra.controller;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import com.innogrid.aims.common.dto.ApiResponse;
import com.innogrid.aims.common.log.PortalLog;
import com.innogrid.aims.infra.dto.VirtualAssetDtos;
import com.innogrid.aims.common.exception.BusinessException;
import com.innogrid.aims.infra.entity.Asset;
import com.innogrid.aims.infra.repository.AssetRepository;
import com.innogrid.aims.infra.service.AssetService;
import com.innogrid.aims.infra.service.VirtualAssetService;
import com.innogrid.aims.monitoring.dto.log.PortalLogType;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

/**
 * 물리/가상 자산 관리 API.
 * [요구사항 #34] 물리/가상 자산 관리 — 사양·위치·상태 등 자산 정보 통합 등록·관리
 * 물리 자산은 1세부 하드웨어 데이터를 수신하여 표출한다 (POST /ingest).
 *
 * 포털 로그(docs/08 §10.1 묶음 7): 프론트 "가상화 자산" 화면이 부르는 가상 호스트·호스트 VM 조회만 {@code @PortalLog(ACCESS)} 로 남긴다.
 * 물리 자산 API 는 프론트가 아직 픽스처를 쓰고(화면 미연결), ingest 는 1세부 서비스 간 호출이라 제외.
 */
@Tag(name = "자산 관리", description = "물리/가상 자산 관리 (물리 자산: 1세부 데이터 수신)")
@RestController
@RequestMapping("/api/v1/assets")
public class AssetController {

    private final AssetRepository assetRepository;
    private final AssetService assetService;
    private final VirtualAssetService virtualAssetService;

    public AssetController(AssetRepository assetRepository, AssetService assetService,
                           VirtualAssetService virtualAssetService) {
        this.assetRepository = assetRepository;
        this.assetService = assetService;
        this.virtualAssetService = virtualAssetService;
    }

    /** 자산 목록 조회 — 분류/상태/출처/키워드 필터 지원 */
    @Operation(summary = "자산 목록",
            description = "mdcId/type/status/categoryMajor/source 필터, keyword는 이름·모델·시리얼 부분 일치")
    @GetMapping
    public ApiResponse<List<Asset>> listAssets(@RequestParam(required = false) String mdcId,
                                               @RequestParam(required = false) String type,
                                               @RequestParam(required = false) String status,
                                               @RequestParam(required = false) String categoryMajor,
                                               @RequestParam(required = false) String source,
                                               @RequestParam(required = false) String keyword) {
        return ApiResponse.ok(assetService.search(
                mdcId,
                type == null ? null : Asset.AssetType.valueOf(type.toUpperCase()),
                status == null ? null : Asset.AssetStatus.valueOf(status.toUpperCase()),
                categoryMajor,
                source == null ? null : Asset.AssetSource.valueOf(source.toUpperCase()),
                keyword));
    }

    /** 1세부 하드웨어 자산 데이터 수신 (일괄 upsert + 랙 실장 동기화) */
    @Operation(summary = "1세부 자산 데이터 수신",
            description = "externalId 기준 upsert. rackLocation이 있으면 랙 실장 위치를 동기화")
    @PostMapping("/ingest")
    public ApiResponse<Map<String, Object>> ingestAssets(
            @RequestBody List<AssetService.IngestItem> items) {
        return ApiResponse.ok(assetService.ingest(items));
    }

    // ── 가상화 자산 (OpenStack 하이퍼바이저 호스트 → VM) ─────────

    /** 가상화 자산 — 호스트 목록 (OpenStack 하이퍼바이저 + 퍼블릭 클라우드 provider·리전) */
    @Operation(summary = "가상화 자산 호스트 목록",
            description = "provider 로 구분: openstack=하이퍼바이저 호스트(Nova, vCore/할당코어/메모리 포함), "
                    + "aws/azure/gcp=Tumblebug 의 provider+리전(=connection). 공개 클라우드는 호스트 사용률 미제공이라 vCore/코어/메모리 null. "
                    + "provider 파라미터(선택)로 분류 필터: 미지정=전체, openstack/aws/azure/gcp")
    @PortalLog(type = PortalLogType.ACCESS, screen = "가상화 자산", action = "조회",
               content = "'가상 호스트 목록 조회 (공급자: ' + (#provider ?: '전체') + ')'")
    @GetMapping("/virtual/hosts")
    public ApiResponse<List<VirtualAssetDtos.HostSummary>> listVirtualHosts(
            @RequestParam(required = false) String provider) {
        return ApiResponse.ok(virtualAssetService.listHosts(provider));
    }

    /** 가상화 자산 — 호스트 하위 VM 목록 (계층 확장) */
    @Operation(summary = "가상화 자산 호스트 하위 VM 목록",
            description = "hostId 확장 시 해당 호스트의 VM 계층 조회. OpenStack=하이퍼바이저 hostname, 퍼블릭 클라우드=connection(provider-리전)")
    @PortalLog(type = PortalLogType.ACCESS, screen = "가상화 자산", action = "조회",
               content = "'가상 호스트 VM 목록 조회: 호스트 ' + #hostId",
               refType = "VIRTUAL_HOST", refId = "#hostId")
    @GetMapping("/virtual/hosts/{hostId}/vms")
    public ApiResponse<List<VirtualAssetDtos.HostVm>> listVirtualHostVms(@PathVariable String hostId) {
        return ApiResponse.ok(virtualAssetService.listHostVms(hostId));
    }

    /** 자산 등록 */
    @PostMapping
    public ApiResponse<Asset> createAsset(@RequestBody Map<String, Object> request) {
        String mdcId = (String) request.get("mdcId");
        Asset.AssetType type = Asset.AssetType.valueOf(((String) request.get("type")).toUpperCase());
        String name = (String) request.get("name");

        Asset asset = new Asset(mdcId, type, name);
        applyOptionalFields(asset, request);
        assetRepository.save(asset);
        return ApiResponse.ok(asset);
    }

    /** 자산 상세 조회 */
    @GetMapping("/{assetId}")
    public ApiResponse<Asset> getAsset(@PathVariable Long assetId) {
        Asset asset = findAsset(assetId);
        return ApiResponse.ok(asset);
    }

    /** 자산 정보 수정 */
    @PutMapping("/{assetId}")
    public ApiResponse<Asset> updateAsset(@PathVariable Long assetId,
                                          @RequestBody Map<String, Object> request) {
        Asset asset = findAsset(assetId);
        if (request.containsKey("name")) asset.setName((String) request.get("name"));
        applyOptionalFields(asset, request);
        assetRepository.save(asset);
        return ApiResponse.ok(asset);
    }

    /** 자산 삭제 */
    @DeleteMapping("/{assetId}")
    public ApiResponse<Void> deleteAsset(@PathVariable Long assetId) {
        assetRepository.deleteById(assetId);
        return ApiResponse.ok(null);
    }

    private Asset findAsset(Long id) {
        return assetRepository.findById(id)
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND, "자산을 찾을 수 없습니다"));
    }

    private void applyOptionalFields(Asset asset, Map<String, Object> request) {
        if (request.containsKey("manufacturer")) asset.setManufacturer((String) request.get("manufacturer"));
        if (request.containsKey("model")) asset.setModel((String) request.get("model"));
        if (request.containsKey("serialNumber")) asset.setSerialNumber((String) request.get("serialNumber"));
        if (request.containsKey("location")) asset.setLocation((String) request.get("location"));
        if (request.containsKey("status")) {
            asset.setStatus(Asset.AssetStatus.valueOf(((String) request.get("status")).toUpperCase()));
        }
        if (request.containsKey("purchaseDate")) {
            asset.setPurchaseDate(LocalDate.parse((String) request.get("purchaseDate")));
        }
        if (request.containsKey("categoryMajor")) asset.setCategoryMajor((String) request.get("categoryMajor"));
        if (request.containsKey("categoryMiddle")) asset.setCategoryMiddle((String) request.get("categoryMiddle"));
        if (request.containsKey("categoryMinor")) asset.setCategoryMinor((String) request.get("categoryMinor"));
        if (request.containsKey("categoryDetail")) asset.setCategoryDetail((String) request.get("categoryDetail"));
        if (request.containsKey("equipmentLocation")) asset.setEquipmentLocation((String) request.get("equipmentLocation"));
        if (request.containsKey("rackLocation")) asset.setRackLocation((String) request.get("rackLocation"));
        if (request.containsKey("mountPoint")) asset.setMountPoint((String) request.get("mountPoint"));
        if (request.containsKey("unitSize")) asset.setUnitSize(((Number) request.get("unitSize")).intValue());
        if (request.containsKey("systemType")) asset.setSystemType((String) request.get("systemType"));
    }
}
