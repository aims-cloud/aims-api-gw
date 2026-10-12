package com.innogrid.aims.dashboard.repository;

import com.innogrid.aims.dashboard.entity.GpuDashboardThresholdSetting;
import org.springframework.data.jpa.repository.JpaRepository;

public interface GpuDashboardThresholdRepository extends JpaRepository<GpuDashboardThresholdSetting, Long> {
}
