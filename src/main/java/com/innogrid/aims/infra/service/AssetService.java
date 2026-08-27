package com.innogrid.aims.infra.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.innogrid.aims.common.exception.BusinessException;
import com.innogrid.aims.infra.entity.Asset;
import com.innogrid.aims.infra.entity.Rack;
import com.innogrid.aims.infra.entity.RackUnit;
import com.innogrid.aims.infra.repository.AssetRepository;
import com.innogrid.aims.infra.repository.RackRepository;
import com.innogrid.aims.infra.repository.RackUnitRepository;

/**
 * 자산 서비스.
 * - 필터/검색: 분류·상태·출처·키워드 동적 조건 조회
 * - 1세부 수신: 하드웨어 자산 데이터 일괄 upsert + 랙 실장 위치 동기화
 *   (1세부가 하드웨어 데이터를 주면 그대로 표출하는 구조. upsert 키 = source + externalId)
 */
@Service
public class AssetService {

    private static final Logger log = LoggerFactory.getLogger(AssetService.class);

    private final AssetRepository assetRepository;
    private final RackRepository rackRepository;
    private final RackUnitRepository rackUnitRepository;

    public AssetService(AssetRepository assetRepository,
                        RackRepository rackRepository,
                        RackUnitRepository rackUnitRepository) {
        this.assetRepository = assetRepository;
        this.rackRepository = rackRepository;
        this.rackUnitRepository = rackUnitRepository;
    }

    // ── 필터/검색 ───────────────────────────────────────────────

    /**
     * 자산 동적 검색.
     * keyword는 이름/모델/시리얼번호 부분 일치.
     */
    public List<Asset> search(String mdcId, Asset.AssetType type, Asset.AssetStatus status,
                              String categoryMajor, Asset.AssetSource source, String keyword) {
        Specification<Asset> spec = (root, query, cb) -> cb.conjunction();

        if (mdcId != null && !mdcId.isBlank()) {
            spec = spec.and((root, query, cb) -> cb.equal(root.get("mdcId"), mdcId));
        }
        if (type != null) {
            spec = spec.and((root, query, cb) -> cb.equal(root.get("type"), type));
        }
        if (status != null) {
            spec = spec.and((root, query, cb) -> cb.equal(root.get("status"), status));
        }
        if (categoryMajor != null && !categoryMajor.isBlank()) {
            spec = spec.and((root, query, cb) -> cb.equal(root.get("categoryMajor"), categoryMajor));
        }
        if (source != null) {
            spec = spec.and((root, query, cb) -> cb.equal(root.get("source"), source));
        }
        if (keyword != null && !keyword.isBlank()) {
            String like = "%" + keyword.toLowerCase() + "%";
            spec = spec.and((root, query, cb) -> cb.or(
                    cb.like(cb.lower(root.get("name")), like),
                    cb.like(cb.lower(cb.coalesce(root.get("model"), "")), like),
                    cb.like(cb.lower(cb.coalesce(root.get("serialNumber"), "")), like)));
        }

        return assetRepository.findAll(spec);
    }

    // ── 1세부 하드웨어 데이터 수신 (일괄 upsert + 랙 동기화) ────

    public record IngestItem(
            String externalId,      // 필수: 1세부 측 자산 식별자
            String name,            // 필수
            String type,            // AssetType (기본 OTHER)
            String mdcId,
            String manufacturer,
            String model,
            String serialNumber,
            String status,          // AssetStatus (기본 ACTIVE)
            String categoryMajor,
            String categoryMiddle,
            String categoryMinor,
            String categoryDetail,
            String equipmentLocation,
            String rackLocation,    // 랙 이름 — 존재하면 랙 실장 동기화
            String mountPoint,      // 랙 내 U 위치
            Integer unitSize,
            String systemType) {}

    /**
     * 1세부 하드웨어 자산 일괄 수신.
     * (source=SEBU1, externalId) 기준 upsert 후 랙 실장 위치를 동기화한다.
     */
    @Transactional
    public Map<String, Object> ingest(List<IngestItem> items) {
        if (items == null || items.isEmpty()) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "수신 데이터가 비어 있습니다");
        }

        int created = 0;
        int updated = 0;
        int rackMapped = 0;
        List<String> rackMissing = new ArrayList<>();
        List<String> skipped = new ArrayList<>();

        for (IngestItem item : items) {
            if (item.externalId() == null || item.externalId().isBlank()
                    || item.name() == null || item.name().isBlank()) {
                skipped.add(String.valueOf(item.externalId()));
                continue;
            }

            Optional<Asset> existing = assetRepository
                    .findBySourceAndExternalId(Asset.AssetSource.SEBU1, item.externalId());

            Asset asset;
            if (existing.isPresent()) {
                asset = existing.get();
                updated++;
            } else {
                asset = new Asset(
                        item.mdcId() == null ? "sebu1" : item.mdcId(),
                        parseType(item.type()),
                        item.name());
                asset.setSource(Asset.AssetSource.SEBU1);
                asset.setExternalId(item.externalId());
                created++;
            }
            applyFields(asset, item);
            assetRepository.save(asset);

            // 랙 실장 위치 동기화
            if (item.rackLocation() != null && !item.rackLocation().isBlank()) {
                boolean mapped = syncRackUnit(asset, item);
                if (mapped) {
                    rackMapped++;
                } else {
                    rackMissing.add(item.rackLocation());
                }
            }
        }

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("received", items.size());
        summary.put("created", created);
        summary.put("updated", updated);
        summary.put("rackMapped", rackMapped);
        summary.put("rackMissing", rackMissing.stream().distinct().toList());
        summary.put("skipped", skipped);
        return summary;
    }

    private void applyFields(Asset asset, IngestItem item) {
        asset.setName(item.name());
        if (item.manufacturer() != null) asset.setManufacturer(item.manufacturer());
        if (item.model() != null) asset.setModel(item.model());
        if (item.serialNumber() != null) asset.setSerialNumber(item.serialNumber());
        if (item.status() != null) asset.setStatus(parseStatus(item.status()));
        if (item.categoryMajor() != null) asset.setCategoryMajor(item.categoryMajor());
        if (item.categoryMiddle() != null) asset.setCategoryMiddle(item.categoryMiddle());
        if (item.categoryMinor() != null) asset.setCategoryMinor(item.categoryMinor());
        if (item.categoryDetail() != null) asset.setCategoryDetail(item.categoryDetail());
        if (item.equipmentLocation() != null) asset.setEquipmentLocation(item.equipmentLocation());
        if (item.rackLocation() != null) asset.setRackLocation(item.rackLocation());
        if (item.mountPoint() != null) asset.setMountPoint(item.mountPoint());
        if (item.unitSize() != null) asset.setUnitSize(item.unitSize());
        if (item.systemType() != null) asset.setSystemType(item.systemType());
    }

    /** 자산의 랙 실장 위치를 RackUnit에 반영한다. 랙이 없으면 false. */
    private boolean syncRackUnit(Asset asset, IngestItem item) {
        Optional<Rack> rack = rackRepository.findFirstByName(item.rackLocation());
        if (rack.isEmpty()) {
            log.warn("랙 실장 동기화 건너뜀 — 랙 없음: {} (자산: {})", item.rackLocation(), asset.getName());
            return false;
        }

        String position = item.mountPoint() == null ? "" : item.mountPoint();
        Optional<RackUnit> existing = rackUnitRepository
                .findFirstByRackIdAndAssetName(rack.get().getId(), asset.getName());
        if (existing.isPresent()) {
            RackUnit unit = existing.get();
            unit.setPosition(position);
            unit.setAssetType(asset.getType().name());
            rackUnitRepository.save(unit);
        } else {
            rackUnitRepository.save(
                    new RackUnit(rack.get(), position, asset.getName(), asset.getType().name()));
        }
        return true;
    }

    private static Asset.AssetType parseType(String type) {
        if (type == null || type.isBlank()) return Asset.AssetType.OTHER;
        try {
            return Asset.AssetType.valueOf(type.toUpperCase());
        } catch (IllegalArgumentException e) {
            return Asset.AssetType.OTHER;
        }
    }

    private static Asset.AssetStatus parseStatus(String status) {
        try {
            return Asset.AssetStatus.valueOf(status.toUpperCase());
        } catch (IllegalArgumentException e) {
            return Asset.AssetStatus.ACTIVE;
        }
    }
}
