package com.innogrid.aims.infra.repository;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import com.innogrid.aims.infra.entity.Asset;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@Transactional
class AssetRepositoryTest {

    @Autowired
    AssetRepository repository;

    @BeforeEach
    void setUp() {
        Asset server = new Asset("mdc-01", Asset.AssetType.SERVER, "sv-01");
        server.setManufacturer("Dell");
        server.setModel("R750");
        repository.save(server);

        Asset gpu = new Asset("mdc-01", Asset.AssetType.GPU_SERVER, "gpu-01");
        gpu.setManufacturer("Dell");
        gpu.setModel("XE9680");
        repository.save(gpu);

        repository.save(new Asset("mdc-02", Asset.AssetType.SERVER, "sv-02"));
    }

    @Test
    void MDC별_자산_조회() {
        List<Asset> assets = repository.findByMdcIdOrderByCreatedAtDesc("mdc-01");
        assertEquals(2, assets.size());
    }

    @Test
    void MDC별_유형별_조회() {
        List<Asset> gpus = repository.findByMdcIdAndTypeOrderByCreatedAtDesc("mdc-01", Asset.AssetType.GPU_SERVER);
        assertEquals(1, gpus.size());
        assertEquals("gpu-01", gpus.get(0).getName());
    }

    @Test
    void 유형별_전체_조회() {
        List<Asset> servers = repository.findByTypeOrderByCreatedAtDesc(Asset.AssetType.SERVER);
        assertEquals(2, servers.size());
    }

    @Test
    void 자산_필드_검증() {
        Asset asset = repository.findByMdcIdOrderByCreatedAtDesc("mdc-01").get(0);
        assertEquals("Dell", asset.getManufacturer());
        assertNotNull(asset.getCreatedAt());
    }
}
