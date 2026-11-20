package com.innogrid.aims.monitoring.repository;

import java.util.List;

import com.innogrid.aims.infra.entity.Asset;
import com.innogrid.aims.infra.repository.AssetRepository;
import com.innogrid.aims.monitoring.entity.MonitoringTarget;
import com.innogrid.aims.monitoring.entity.MonitoringTarget.ExporterType;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Transactional
class MonitoringTargetRepositoryTest {

    @Autowired
    MonitoringTargetRepository monitoringTargetRepository;
    @Autowired
    AssetRepository assetRepository;

    @Test
    void 자산_ID_목록으로_연결된_exporter를_조회한다() {
        Asset node01 = assetRepository.save(new Asset("mdc-01", Asset.AssetType.GPU_SERVER, "node 01"));
        Asset node02 = assetRepository.save(new Asset("mdc-01", Asset.AssetType.SERVER, "node 02"));

        MonitoringTarget nodeExporter = new MonitoringTarget("mdc-01", "node 01", "10.0.0.1", 9100, ExporterType.NODE_EXPORTER);
        nodeExporter.setAsset(node01);
        MonitoringTarget dcgm = new MonitoringTarget("mdc-01", "node 01", "10.0.0.1", 9400, ExporterType.DCGM_EXPORTER);
        dcgm.setAsset(node01);
        MonitoringTarget unlinked = new MonitoringTarget("mdc-01", "node 02", "10.0.0.2", 9100, ExporterType.NODE_EXPORTER);
        monitoringTargetRepository.saveAll(List.of(nodeExporter, dcgm, unlinked));

        // 자원 탐색기 트리 조회 경로 — 역할 스코프의 자산 목록으로 exporter 를 한 번에 얻는다
        List<MonitoringTarget> linked = monitoringTargetRepository.findByAsset_IdIn(List.of(node01.getId(), node02.getId()));
        assertThat(linked).extracting(MonitoringTarget::getExporterType)
                .containsExactlyInAnyOrder(ExporterType.NODE_EXPORTER, ExporterType.DCGM_EXPORTER);
        assertThat(linked).allSatisfy(t -> assertThat(t.getAsset().getId()).isEqualTo(node01.getId()));

        // asset_id 가 비어 있는 타겟은 hostname 이 같아도 연결하지 않는다 (이름 추정 금지)
        assertThat(monitoringTargetRepository.findByAsset_IdIn(List.of(node02.getId()))).isEmpty();
        assertThat(monitoringTargetRepository.findByMdcId("mdc-01")).hasSize(3);
    }
}
