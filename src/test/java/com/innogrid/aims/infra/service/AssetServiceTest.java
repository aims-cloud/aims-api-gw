package com.innogrid.aims.infra.service;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;

import com.innogrid.aims.common.exception.BusinessException;
import com.innogrid.aims.infra.entity.Asset;
import com.innogrid.aims.infra.entity.Rack;
import com.innogrid.aims.infra.entity.RackUnit;
import com.innogrid.aims.infra.repository.AssetRepository;
import com.innogrid.aims.infra.repository.RackRepository;
import com.innogrid.aims.infra.repository.RackUnitRepository;
import com.innogrid.aims.infra.service.AssetService.IngestItem;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AssetServiceTest {

    private AssetRepository assetRepository;
    private RackRepository rackRepository;
    private RackUnitRepository rackUnitRepository;
    private AssetService service;

    @BeforeEach
    void setUp() {
        assetRepository = mock(AssetRepository.class);
        rackRepository = mock(RackRepository.class);
        rackUnitRepository = mock(RackUnitRepository.class);
        service = new AssetService(assetRepository, rackRepository, rackUnitRepository);
        when(assetRepository.save(any(Asset.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    private static IngestItem item(String externalId, String name, String rackLocation) {
        return new IngestItem(externalId, name, "SERVER", "mdc-1",
                "Dell", "R750", "SN-001", "ACTIVE",
                "하드웨어", "서버", "GPU 서버", null,
                "본관 3층 A구역", rackLocation, "U10", 2, "x86");
    }

    @Test
    void ingest_creates_new_asset_from_sebu1_data() {
        when(assetRepository.findBySourceAndExternalId(Asset.AssetSource.SEBU1, "hw-001"))
                .thenReturn(Optional.empty());

        Map<String, Object> summary = service.ingest(List.of(item("hw-001", "gpu-node-01", null)));

        assertThat(summary)
                .containsEntry("received", 1)
                .containsEntry("created", 1)
                .containsEntry("updated", 0);

        ArgumentCaptor<Asset> captor = ArgumentCaptor.forClass(Asset.class);
        verify(assetRepository).save(captor.capture());
        Asset saved = captor.getValue();
        assertThat(saved.getSource()).isEqualTo(Asset.AssetSource.SEBU1);
        assertThat(saved.getExternalId()).isEqualTo("hw-001");
        assertThat(saved.getCategoryMajor()).isEqualTo("하드웨어");
        assertThat(saved.getEquipmentLocation()).isEqualTo("본관 3층 A구역");
        assertThat(saved.getUnitSize()).isEqualTo(2);
        assertThat(saved.getSystemType()).isEqualTo("x86");
    }

    @Test
    void ingest_updates_existing_asset_by_external_id() {
        Asset existing = new Asset("mdc-1", Asset.AssetType.SERVER, "old-name");
        existing.setSource(Asset.AssetSource.SEBU1);
        existing.setExternalId("hw-001");
        when(assetRepository.findBySourceAndExternalId(Asset.AssetSource.SEBU1, "hw-001"))
                .thenReturn(Optional.of(existing));

        Map<String, Object> summary = service.ingest(List.of(item("hw-001", "renamed-node", null)));

        assertThat(summary)
                .containsEntry("created", 0)
                .containsEntry("updated", 1);
        assertThat(existing.getName()).isEqualTo("renamed-node");
    }

    @Test
    void ingest_syncs_rack_unit_when_rack_exists() {
        when(assetRepository.findBySourceAndExternalId(any(), any())).thenReturn(Optional.empty());
        Rack rack = new Rack("mdc-1", "RACK-A01", 42, 10.0);
        when(rackRepository.findFirstByName("RACK-A01")).thenReturn(Optional.of(rack));
        when(rackUnitRepository.findFirstByRackIdAndAssetName(any(), any()))
                .thenReturn(Optional.empty());

        Map<String, Object> summary = service.ingest(List.of(item("hw-001", "gpu-node-01", "RACK-A01")));

        assertThat(summary).containsEntry("rackMapped", 1);
        ArgumentCaptor<RackUnit> captor = ArgumentCaptor.forClass(RackUnit.class);
        verify(rackUnitRepository).save(captor.capture());
        assertThat(captor.getValue().getPosition()).isEqualTo("U10");
        assertThat(captor.getValue().getAssetName()).isEqualTo("gpu-node-01");
    }

    @Test
    void ingest_reports_missing_rack_without_failing() {
        when(assetRepository.findBySourceAndExternalId(any(), any())).thenReturn(Optional.empty());
        when(rackRepository.findFirstByName("RACK-NOPE")).thenReturn(Optional.empty());

        Map<String, Object> summary = service.ingest(List.of(item("hw-001", "node", "RACK-NOPE")));

        assertThat(summary)
                .containsEntry("created", 1)
                .containsEntry("rackMapped", 0);
        assertThat(summary.get("rackMissing")).isEqualTo(List.of("RACK-NOPE"));
        verify(rackUnitRepository, never()).save(any());
    }

    @Test
    void ingest_skips_items_without_required_fields() {
        Map<String, Object> summary = service.ingest(List.of(
                new IngestItem(null, "no-external-id", null, null, null, null, null, null,
                        null, null, null, null, null, null, null, null, null)));

        assertThat(summary)
                .containsEntry("created", 0)
                .containsEntry("updated", 0);
        assertThat((List<?>) summary.get("skipped")).hasSize(1);
        verify(assetRepository, never()).save(any());
    }

    @Test
    void ingest_empty_list_throws_bad_request() {
        assertThatThrownBy(() -> service.ingest(List.of()))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getStatus())
                        .isEqualTo(HttpStatus.BAD_REQUEST));
    }

    @Test
    void ingest_unknown_type_defaults_to_other() {
        when(assetRepository.findBySourceAndExternalId(any(), any())).thenReturn(Optional.empty());
        IngestItem unknownType = new IngestItem("hw-002", "mystery-box", "QUANTUM", null,
                null, null, null, null, null, null, null, null, null, null, null, null, null);

        service.ingest(List.of(unknownType));

        ArgumentCaptor<Asset> captor = ArgumentCaptor.forClass(Asset.class);
        verify(assetRepository).save(captor.capture());
        assertThat(captor.getValue().getType()).isEqualTo(Asset.AssetType.OTHER);
    }
}
