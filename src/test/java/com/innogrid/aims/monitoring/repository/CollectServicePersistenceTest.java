package com.innogrid.aims.monitoring.repository;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;
import java.util.UUID;
import com.innogrid.aims.infra.entity.Asset;
import com.innogrid.aims.monitoring.dto.resourceexplorer.node.service.CollectServiceRequest;
import com.innogrid.aims.monitoring.entity.CollectService;
import org.hibernate.SessionFactory;
import org.hibernate.cfg.Configuration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

/** 별도 H2 DB에서 매핑과 배포 SQL의 FK/유일 제약/원장 교체를 검증한다. 외부 서비스를 시작하지 않는다. */
class CollectServicePersistenceTest {
    SessionFactory factory;
    @BeforeEach void setup() {
        factory = new Configuration().addAnnotatedClass(Asset.class).addAnnotatedClass(CollectService.class)
                .setProperty("hibernate.connection.url", "jdbc:h2:mem:collect-" + UUID.randomUUID())
                .setProperty("hibernate.connection.driver_class", "org.h2.Driver")
                .setProperty("hibernate.hbm2ddl.auto", "create-drop").buildSessionFactory();
    }
    @AfterEach void close() { if (factory != null) factory.close(); }

    @Test void roundTripReplaceChildrenAndDeleteService() {
        Long id = factory.fromTransaction(session -> {
            Asset asset = new Asset("mdc", Asset.AssetType.GPU_SERVER, "host");
            session.persist(asset);
            CollectService service = new CollectService(asset, "user@example.com");
            service.update(request("/old"));
            session.persist(service);
            return service.getId();
        });
        factory.inTransaction(session -> {
            CollectService service = session.find(CollectService.class, id);
            assertThat(service.getLogPaths()).containsExactly("/old");
            assertThat(service.getCreatedAt()).isNotNull();
            service.update(request("/new"));
        });
        factory.inTransaction(session -> {
            CollectService service = session.find(CollectService.class, id);
            assertThat(service.getLogPaths()).containsExactly("/new");
            assertThat(service.getTracePorts()).containsExactly(8080);
            session.remove(service);
        });
        factory.inTransaction(session -> {
            assertThat(session.createNativeQuery("select count(*) from collect_service_log_paths", Long.class).getSingleResult()).isZero();
            assertThat(session.createNativeQuery("select count(*) from collect_service_trace_ports", Long.class).getSingleResult()).isZero();
        });
    }

    @Test void assetDeletionCascadesToRegistryAndChildren() {
        Long assetId = factory.fromTransaction(session -> {
            Asset asset = new Asset("mdc", Asset.AssetType.GPU_SERVER, "host");
            session.persist(asset);
            CollectService service = new CollectService(asset, "user@example.com");
            service.update(request("/a"));
            session.persist(service);
            return asset.getId();
        });
        factory.inTransaction(session -> session.remove(session.find(Asset.class, assetId)));
        factory.inTransaction(session -> {
            assertThat(session.createNativeQuery("select count(*) from collect_services", Long.class).getSingleResult()).isZero();
            assertThat(session.createNativeQuery("select count(*) from collect_service_log_paths", Long.class).getSingleResult()).isZero();
        });
    }

    @Test void deploymentSqlEnforcesUniquePortAndCascades() throws Exception {
        try (Connection connection = DriverManager.getConnection("jdbc:h2:mem:sql-" + UUID.randomUUID() + ";MODE=PostgreSQL");
             Statement statement = connection.createStatement()) {
            statement.execute("create table assets(id bigint primary key)");
            String sql = Files.readString(Path.of("deploy/sql/2026-09-11_collect_services.sql"));
            for (String command : sql.split(";")) if (!command.isBlank()) statement.execute(command);
            statement.execute("insert into assets values(1)");
            statement.execute("insert into collect_services(asset_id, service_name) values(1,'service')");
            assertThatThrownBy(() -> statement.execute("insert into collect_services(asset_id, service_name) values(1,'service')"))
                    .isInstanceOf(java.sql.SQLException.class);
            assertThatThrownBy(() -> statement.execute("insert into collect_service_trace_ports values(1,65536)"))
                    .isInstanceOf(java.sql.SQLException.class);
            statement.execute("insert into collect_service_log_paths values(1,'/a')");
            statement.execute("insert into collect_service_trace_ports values(1,8080)");
            statement.execute("delete from assets where id=1");
            try (ResultSet result = statement.executeQuery("select count(*) from collect_service_log_paths")) {
                result.next();
                assertThat(result.getLong(1)).isZero();
            }
        }
    }

    private CollectServiceRequest request(String path) {
        return new CollectServiceRequest("service", null,
                new CollectServiceRequest.LogCollection(true, false, List.of(path, path)),
                new CollectServiceRequest.TraceCollection(true, List.of(8080, 8080)));
    }
}
