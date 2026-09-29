package com.innogrid.aims.monitoring.repository;

import java.time.OffsetDateTime;
import java.util.UUID;
import com.innogrid.aims.infra.entity.Asset;
import com.innogrid.aims.monitoring.entity.AgentInstallStatus;
import com.innogrid.aims.monitoring.entity.AgentNode;
import com.innogrid.aims.monitoring.entity.AgentTransport;
import org.hibernate.SessionFactory;
import org.hibernate.cfg.Configuration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

/** 별도 H2 DB에서 에이전트 원장 매핑(자산 1:1, 기본값, 적용 상태 전이, 자산 삭제 cascade)을 검증한다. */
class AgentNodePersistenceTest {
    SessionFactory factory;
    @BeforeEach void setup() {
        factory = new Configuration().addAnnotatedClass(Asset.class).addAnnotatedClass(AgentNode.class)
                .setProperty("hibernate.connection.url", "jdbc:h2:mem:agent-" + UUID.randomUUID())
                .setProperty("hibernate.connection.driver_class", "org.h2.Driver")
                .setProperty("hibernate.hbm2ddl.auto", "create-drop").buildSessionFactory();
    }
    @AfterEach void close() { if (factory != null) factory.close(); }

    @Test void nodeSharesAssetIdAndTracksTargetsApplyState() {
        Long assetId = factory.fromTransaction(session -> {
            Asset asset = new Asset("mdc", Asset.AssetType.GPU_SERVER, "k8s-cp");
            session.persist(asset);
            AgentNode node = new AgentNode(asset, AgentTransport.LOCAL, "/opt/monitoring/alloy-targets/log-targets.json");
            session.persist(node);
            return asset.getId();
        });
        factory.inTransaction(session -> {
            AgentNode node = session.find(AgentNode.class, assetId);
            assertThat(node.getAssetId()).isEqualTo(assetId);
            assertThat(node.getTransport()).isEqualTo(AgentTransport.LOCAL);
            assertThat(node.getInstallStatus()).isEqualTo(AgentInstallStatus.NOT_INSTALLED);
            assertThat(node.getSshSecret()).isNull();
            assertThat(node.getCreatedAt()).isNotNull();
            assertThat(node.isTargetsPending()).isFalse();          // 아직 원하는 목록이 없음
            node.markDesired("h1");
            assertThat(node.isTargetsPending()).isTrue();
        });
        factory.inTransaction(session -> {
            AgentNode node = session.find(AgentNode.class, assetId);
            node.markApplyFailed("permission denied");
            assertThat(node.getTargetsError()).isEqualTo("permission denied");
            node.markApplied("h1", OffsetDateTime.now());
            assertThat(node.isTargetsPending()).isFalse();
            assertThat(node.getTargetsError()).isNull();
        });
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
