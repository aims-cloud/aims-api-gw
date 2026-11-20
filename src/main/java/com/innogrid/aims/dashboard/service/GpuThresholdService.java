package com.innogrid.aims.dashboard.service;

import com.innogrid.aims.common.exception.BusinessException;
import com.innogrid.aims.dashboard.GpuDashboardProperties;
import com.innogrid.aims.dashboard.dto.UpdateGpuThresholdRequest;
import com.innogrid.aims.dashboard.entity.GpuDashboardThresholdSetting;
import com.innogrid.aims.dashboard.model.GpuDashboardThreshold;
import com.innogrid.aims.dashboard.repository.GpuDashboardThresholdRepository;
import com.innogrid.aims.mock.MockDataProvider;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * GPU 대시보드 임계값 — DB 단일 행(gpu_dashboard_thresholds)에 영속하는 전역 공용 값 (Confluence 2320072739 R-21).
 * 행이 없으면 기본값(mock 모드면 mock 데이터셋의 값)을 돌려주고, 첫 변경 때 행을 만든다.
 */
@Service
public class GpuThresholdService {

    private final GpuDashboardThresholdRepository repository;
    // TODO:: 실제 Thanos 연동이 끝나 Mock 전환이 불필요해지면 두 의존성 제거
    private final GpuDashboardProperties properties;
    private final MockDataProvider mockDataProvider;

    public GpuThresholdService(GpuDashboardThresholdRepository repository, GpuDashboardProperties properties,
                               MockDataProvider mockDataProvider) {
        this.repository = repository;
        this.properties = properties;
        this.mockDataProvider = mockDataProvider;
    }

    @Transactional(readOnly = true)
    public GpuDashboardThreshold current() {
        return repository.findById(GpuDashboardThresholdSetting.SINGLETON_ID)
                .map(GpuDashboardThresholdSetting::toThreshold)
                .orElseGet(this::defaults);
    }

    /**
     * 임계값 변경. 유휴가 고활용 사용률보다 크면 400. 활성 값을 생략한 요청(기존 클라이언트)은 현재 활성 값을 유지한다.
     */
    @Transactional
    public GpuDashboardThreshold update(UpdateGpuThresholdRequest request) {
        if (request.idleGpuUtilization() > request.highGpuUtilization()) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "유휴 GPU 임계치는 고활용 GPU 임계치보다 클 수 없습니다.");
        }
        GpuDashboardThresholdSetting row = repository.findById(GpuDashboardThresholdSetting.SINGLETON_ID).orElse(null);
        double active = request.activeGpuUtilization() != null
                ? request.activeGpuUtilization()
                : (row != null ? row.getActiveGpuUtilization() : defaults().activeGpuUtilization());
        GpuDashboardThreshold value = new GpuDashboardThreshold(active, request.idleGpuUtilization(),
                request.highGpuUtilization(), request.highVramUtilization());
        if (row == null) {
            row = GpuDashboardThresholdSetting.of(value, currentUser());
        } else {
            row.apply(value, currentUser());
        }
        return repository.save(row).toThreshold();
    }

    private GpuDashboardThreshold defaults() {
        if (properties.isMockEnabled()) {
            GpuDashboardThreshold mock = mockDataProvider.getGpuDashboardDataset().thresholds();
            if (mock != null) {
                return mock;
            }
        }
        return GpuDashboardThreshold.DEFAULT;
    }

    private static String currentUser() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth == null ? null : auth.getName();
    }
}
