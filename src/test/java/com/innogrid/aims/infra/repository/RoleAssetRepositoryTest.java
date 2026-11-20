package com.innogrid.aims.infra.repository;

import java.util.List;

import com.innogrid.aims.auth.entity.Role;
import com.innogrid.aims.auth.repository.RoleRepository;
import com.innogrid.aims.infra.entity.Asset;
import com.innogrid.aims.infra.entity.RoleAsset;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Transactional
class RoleAssetRepositoryTest {

    @Autowired
    RoleAssetRepository roleAssetRepository;
    @Autowired
    RoleRepository roleRepository;
    @Autowired
    AssetRepository assetRepository;

    @Test
    void 역할_이름으로_매핑된_자산을_조회한다() {
        Role admin = roleRepository.save(new Role("EXPLORER_ADMIN", "자원 탐색기 테스트 역할 A"));
        Role user = roleRepository.save(new Role("EXPLORER_USER", "자원 탐색기 테스트 역할 B"));
        Asset node01 = assetRepository.save(new Asset("mdc-01", Asset.AssetType.GPU_SERVER, "node 01"));
        Asset node02 = assetRepository.save(new Asset("mdc-01", Asset.AssetType.GPU_SERVER, "node 02"));

        roleAssetRepository.save(new RoleAsset(admin, node01));
        roleAssetRepository.save(new RoleAsset(user, node02));
        roleAssetRepository.save(new RoleAsset(user, node01));   // 자산 하나가 여러 역할에 노출되는 N:M

        // 토큰 역할 스코프 조회 — 역할 이름 목록으로 매핑된 자산을 얻는다
        List<RoleAsset> adminScope = roleAssetRepository.findByRole_NameIn(List.of("EXPLORER_ADMIN"));
        assertThat(adminScope).extracting(m -> m.getAsset().getName()).containsExactly("node 01");

        List<RoleAsset> bothScope = roleAssetRepository.findByRole_NameIn(
                List.of("EXPLORER_ADMIN", "EXPLORER_USER"));
        assertThat(bothScope).hasSize(3);

        // 자산 → 소속 역할 역방향 조회
        assertThat(roleAssetRepository.findByAsset_Id(node01.getId())).hasSize(2);
        assertThat(roleAssetRepository.existsByRole_IdAndAsset_Id(admin.getId(), node02.getId())).isFalse();
    }
}
