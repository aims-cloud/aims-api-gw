package com.innogrid.aims.infra.controller;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import com.innogrid.aims.common.dto.ApiResponse;
import com.innogrid.aims.common.exception.BusinessException;
import com.innogrid.aims.infra.entity.Asset;
import com.innogrid.aims.infra.repository.AssetRepository;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

/**
 * 물리/가상 자산 관리 API (MDC 자체 관리).
 * [요구사항 #34] 물리/가상 자산 관리 — 사양·위치·상태 등 자산 정보 통합 등록·관리
 */
@Tag(name = "자산 관리", description = "물리/가상 자산 관리")
@RestController
@RequestMapping("/api/v1/assets")
public class AssetController {

    private final AssetRepository assetRepository;

    public AssetController(AssetRepository assetRepository) {
        this.assetRepository = assetRepository;
    }

    /** 자산 목록 조회 */
    @GetMapping
    public ApiResponse<List<Asset>> listAssets(@RequestParam(required = false) String mdcId,
                                               @RequestParam(required = false) String type) {
        List<Asset> assets;
        if (mdcId != null && type != null) {
            Asset.AssetType assetType = Asset.AssetType.valueOf(type.toUpperCase());
            assets = assetRepository.findByMdcIdAndTypeOrderByCreatedAtDesc(mdcId, assetType);
        } else if (mdcId != null) {
            assets = assetRepository.findByMdcIdOrderByCreatedAtDesc(mdcId);
        } else if (type != null) {
            Asset.AssetType assetType = Asset.AssetType.valueOf(type.toUpperCase());
            assets = assetRepository.findByTypeOrderByCreatedAtDesc(assetType);
        } else {
            assets = assetRepository.findAll();
        }
        return ApiResponse.ok(assets);
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
    }
}
