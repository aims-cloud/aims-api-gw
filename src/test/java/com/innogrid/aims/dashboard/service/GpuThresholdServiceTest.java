package com.innogrid.aims.dashboard.service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Optional;
import java.util.UUID;
import com.innogrid.aims.common.exception.BusinessException;
import com.innogrid.aims.dashboard.GpuDashboardProperties;
import com.innogrid.aims.dashboard.dto.UpdateGpuThresholdRequest;
import com.innogrid.aims.dashboard.entity.GpuDashboardThresholdSetting;
import com.innogrid.aims.dashboard.model.GpuDashboardThreshold;
import com.innogrid.aims.dashboard.repository.GpuDashboardThresholdRepository;
import com.innogrid.aims.mock.MockDataProvider;
import org.hibernate.SessionFactory;
import org.hibernate.cfg.Configuration;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** 임계값 영속 — 행 없음 기본값, 첫 변경에 행 생성, 활성 생략 시 유지, 유휴 > 고활용 400, 배포 SQL·매핑 일치. */
class GpuThresholdServiceTest {

    GpuDashboardThresholdRepository repository = mock(GpuDashboardThresholdRepository.class);
    GpuDashboardProperties properties = new GpuDashboardProperties();
    GpuThresholdService service = new GpuThresholdService(repository, properties, mock(MockDataProvider.class));

    @Test void defaultsWhenNoRowAndFirstUpdateCreatesSingletonRow() {
        when(repository.findById(1L)).thenReturn(Optional.empty());
        assertThat(service.current()).isEqualTo(GpuDashboardThreshold.DEFAULT);

        when(repository.save(any(GpuDashboardThresholdSetting.class))).thenAnswer(inv -> inv.getArgument(0));
        GpuDashboardThreshold saved = service.update(new UpdateGpuThresholdRequest(5, 70, 65, null));
        assertThat(saved).isEqualTo(new GpuDashboardThreshold(10, 5, 70, 65));          // 활성 생략 → 기본값 유지
        verify(repository).save(argThat(row -> row.getId() == 1L));
    }

    @Test void omittedActiveKeepsStoredValueAndExplicitActiveReplacesIt() {
        GpuDashboardThresholdSetting row = GpuDashboardThresholdSetting.of(new GpuDashboardThreshold(25, 10, 60, 60), "a");
        when(repository.findById(1L)).thenReturn(Optional.of(row));
        when(repository.save(any(GpuDashboardThresholdSetting.class))).thenAnswer(inv -> inv.getArgument(0));

        assertThat(service.update(new UpdateGpuThresholdRequest(8, 60, 60, null)).activeGpuUtilization()).isEqualTo(25);
        assertThat(service.update(new UpdateGpuThresholdRequest(8, 60, 60, 30.0)).activeGpuUtilization()).isEqualTo(30);
        assertThat(service.current().idleGpuUtilization()).isEqualTo(8);
    }

    @Test void idleAboveHighIsBadRequest() {
        assertThatThrownBy(() -> service.update(new UpdateGpuThresholdRequest(70, 60, 60, 10.0)))
                .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST));
        verify(repository, never()).save(any());
    }

    @Test void deploymentSqlSeedsDefaultsAndMatchesEntityMapping() throws Exception {
        String url = "jdbc:h2:mem:gpu-th-" + UUID.randomUUID() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1";
        try (Connection connection = DriverManager.getConnection(url); Statement statement = connection.createStatement()) {
            String sql = Files.readString(Path.of("deploy/sql/2026-10-06_gpu_dashboard_thresholds.sql"));
            for (String command : sql.split(";")) {
                if (!command.isBlank() && !command.contains("OWNER TO")) {
                    statement.execute(command);
                }
            }
            try (ResultSet rs = statement.executeQuery("select active_gpu_utilization, idle_gpu_utilization, "
                    + "high_gpu_utilization, high_vram_utilization from gpu_dashboard_thresholds where id = 1")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getDouble(1)).isEqualTo(10);
                assertThat(rs.getDouble(2)).isEqualTo(10);
                assertThat(rs.getDouble(3)).isEqualTo(60);
                assertThat(rs.getDouble(4)).isEqualTo(60);
            }
            assertThatThrownBy(() -> statement.execute("insert into gpu_dashboard_thresholds (id, active_gpu_utilization, "
                    + "idle_gpu_utilization, high_gpu_utilization, high_vram_utilization) values (2, 1, 1, 1, 1)"))
                    .satisfies(e -> assertThat(e.getMessage()).containsIgnoringCase("ck_gpu_dashboard_thresholds_singleton"));          // 단일 행
            assertThatThrownBy(() -> statement.execute("update gpu_dashboard_thresholds set idle_gpu_utilization = 90 where id = 1"))
                    .satisfies(e -> assertThat(e.getMessage()).containsIgnoringCase("ck_gpu_dashboard_thresholds_range"));              // 유휴 ≤ 고활용

            // 운영은 ddl-auto: validate — 엔티티가 배포 SQL 이 만든 표와 맞아야 기동한다
            try (SessionFactory factory = new Configuration().addAnnotatedClass(GpuDashboardThresholdSetting.class)
                    .setProperty("hibernate.connection.url", url)
                    .setProperty("hibernate.connection.driver_class", "org.h2.Driver")
                    .setProperty("hibernate.hbm2ddl.auto", "validate").buildSessionFactory()) {
                GpuDashboardThresholdSetting row = factory.fromTransaction(s -> s.find(GpuDashboardThresholdSetting.class, 1L));
                assertThat(row.toThreshold()).isEqualTo(GpuDashboardThreshold.DEFAULT);
                assertThat(row.getUpdatedBy()).isEqualTo("deploy-sql");
            }
        }
    }
}
