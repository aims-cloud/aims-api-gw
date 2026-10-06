package com.innogrid.aims.monitoring.repository;

import java.time.OffsetDateTime;
import java.util.UUID;
import com.innogrid.aims.infra.entity.Asset;
import com.innogrid.aims.monitoring.entity.AgentInstallStatus;
import com.innogrid.aims.monitoring.entity.AgentNode;
import com.innogrid.aims.monitoring.entity.AgentTransport;
import com.innogrid.aims.monitoring.entity.CollectOwner;
import org.hibernate.SessionFactory;
import org.hibernate.cfg.Configuration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

/** 별도 H2 DB에서 에이전트 원장 매핑(서로게이트 id, 자산 1:1·VM 1:1, 기본값, 적용·설치 상태 전이, 자산 삭제 cascade)을 검증한다. */
class AgentNodePersistenceTest {
    SessionFactory factory;
    @BeforeEach void setup() {
        factory = new Configuration().addAnnotatedClass(Asset.class).addAnnotatedClass(AgentNode.class)
                .setProperty("hibernate.connection.url", "jdbc:h2:mem:agent-" + UUID.randomUUID())
                .setProperty("hibernate.connection.driver_class", "org.h2.Driver")
                .setProperty("hibernate.hbm2ddl.auto", "create-drop").buildSessionFactory();
    }
    @AfterEach void close() { if (factory != null) factory.close(); }

    @Test void assetNodeTracksTargetsApplyState() {
        Long[] ids = factory.fromTransaction(session -> {
            session.persist(new Asset("mdc", Asset.AssetType.GPU_SERVER, "other"));    // 자산 id 와 에이전트 id 가 어긋나게
            Asset asset = new Asset("mdc", Asset.AssetType.GPU_SERVER, "k8s-cp");
            session.persist(asset);
            AgentNode node = new AgentNode(asset, AgentTransport.LOCAL, "/opt/monitoring/alloy-targets/log-targets.json");
            session.persist(node);
            return new Long[] {node.getId(), asset.getId()};
        });
        Long agentId = ids[0];
        Long assetId = ids[1];
        assertThat(agentId).isNotEqualTo(assetId);
        factory.inTransaction(session -> {
            AgentNode node = session.find(AgentNode.class, agentId);
            assertThat(node.getAssetId()).isEqualTo(assetId);
            assertThat(node.owner()).isEqualTo(CollectOwner.asset(assetId));
            assertThat(node.getTbNs()).isNull();
            assertThat(node.getTransport()).isEqualTo(AgentTransport.LOCAL);
            assertThat(node.getInstallStatus()).isEqualTo(AgentInstallStatus.NOT_INSTALLED);
            assertThat(node.getSshSecret()).isNull();
            assertThat(node.getCreatedAt()).isNotNull();
            assertThat(node.isTargetsPending()).isFalse();          // 아직 원하는 목록이 없음
            node.markDesired("h1");
            assertThat(node.isTargetsPending()).isTrue();
        });
        factory.inTransaction(session -> {
            AgentNode node = session.find(AgentNode.class, agentId);
            node.markApplyFailed("permission denied");
            assertThat(node.getTargetsError()).isEqualTo("permission denied");
            node.markApplied("h1", OffsetDateTime.now());
            assertThat(node.isTargetsPending()).isFalse();
            assertThat(node.getTargetsError()).isNull();
        });
    }

    @Test void vmNodeIsKeyedByTumblebugIdsAndRecordsInstallProgress() {
        CollectOwner vm = CollectOwner.vm("ns-1", "infra-1", "vm-1");
        Long agentId = factory.fromTransaction(session -> {
            AgentNode node = AgentNode.forVm(vm);
            session.persist(node);
            return node.getId();
        });
        factory.inTransaction(session -> {
            AgentNode node = session.find(AgentNode.class, agentId);
            assertThat(node.owner()).isEqualTo(vm);
            assertThat(node.getAssetId()).isNull();
            assertThat(node.getTransport()).isEqualTo(AgentTransport.PULL);
            node.markInstall(AgentInstallStatus.INSTALLING, "node_exporter", null, null);
        });
        factory.inTransaction(session -> {
            AgentNode node = session.find(AgentNode.class, agentId);
            assertThat(node.getInstallStatus()).isEqualTo(AgentInstallStatus.INSTALLING);
            assertThat(node.getInstallStep()).isEqualTo("node_exporter");
            node.markInstall(AgentInstallStatus.INSTALLED, "done", null, "v1.20.0");
        });
        factory.inTransaction(session -> {
            AgentNode node = session.find(AgentNode.class, agentId);
            assertThat(node.getInstalledAt()).isNotNull();
            assertThat(node.getAgentVersion()).isEqualTo("v1.20.0");
        });
        assertThatThrownBy(() -> factory.inTransaction(session -> session.persist(AgentNode.forVm(vm))))
                .isInstanceOf(RuntimeException.class);                       // 같은 VM 두 행 금지
    }

    @Test void assetDeletionRemovesAgentRow() {
        Long assetId = factory.fromTransaction(session -> {
            Asset asset = new Asset("mdc", Asset.AssetType.GPU_SERVER, "node");
            session.persist(asset);
            session.persist(new AgentNode(asset, AgentTransport.NONE, null));
            return asset.getId();
        });
        factory.inTransaction(session -> session.remove(session.find(Asset.class, assetId)));
        factory.inTransaction(session ->
                assertThat(session.createNativeQuery("select count(*) from agent_nodes", Long.class).getSingleResult()).isZero());
    }
}
