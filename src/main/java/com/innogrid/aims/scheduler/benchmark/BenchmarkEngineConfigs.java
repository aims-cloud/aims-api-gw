package com.innogrid.aims.scheduler.benchmark;

import java.util.HashMap;
import java.util.function.Consumer;

import com.innogrid.aims.scheduler.config.SchedulerProperties;

/**
 * 엔진 설정 변종 — <b>기본 설정의 사본</b>에 노브 하나만 바꿔 열 하나를 만든다.
 *
 * <p>{@link SchedulerProperties}에는 복사 생성자가 없어 Power·Thermal·Network 하위 필드를 손으로 옮긴다. 필드를 하나 빠뜨리면
 * 그 열은 "노브 하나만 다른 설정"이 아니라 기본값이 섞인 다른 설정이 되므로, {@code BenchmarkEngineConfigsTest}가 세 하위 클래스의
 * getter 전량을 반사로 훑어 원본과 같은지 고정한다({@code SchedulingPolicyCopyTest}와 같은 형태). 사본은 스프링 컨텍스트에 등록되지
 * 않는다 — 판 경로 전용 객체다.</p>
 */
public final class BenchmarkEngineConfigs {

    private BenchmarkEngineConfigs() {
    }

    /** 기본 설정을 전부 옮긴 사본에 전력 노브 조정 하나를 얹는다. */
    public static SchedulerProperties withPower(SchedulerProperties base, Consumer<SchedulerProperties.Power> tweak) {
        SchedulerProperties copy = copyOf(base);
        tweak.accept(copy.getPower());
        return copy;
    }

    /** Power·Thermal·Network 하위 필드 전량을 옮긴 비영속 사본. */
    public static SchedulerProperties copyOf(SchedulerProperties src) {
        SchedulerProperties copy = new SchedulerProperties();

        SchedulerProperties.Power p = src.getPower();
        SchedulerProperties.Power q = copy.getPower();
        q.setDefaultCapacityWatts(p.getDefaultCapacityWatts());
        q.setCapacityByNode(p.getCapacityByNode() == null ? null : new HashMap<>(p.getCapacityByNode()));
        q.setIdleRatio(p.getIdleRatio());
        q.setIdleFromMeasurement(p.isIdleFromMeasurement());
        q.setPeiNormalizer(p.getPeiNormalizer());
        q.setResourceShare(p.getResourceShare());
        q.setPeiWeight(p.getPeiWeight());
        q.setWasteWeight(p.getWasteWeight());
        q.setMarginalWeight(p.getMarginalWeight());
        q.setCurveAlpha(p.getCurveAlpha());
        q.setActiveCpuPercent(p.getActiveCpuPercent());
        q.setMarginalScaleWatts(p.getMarginalScaleWatts());
        q.setMaxConsolidationCandidates(p.getMaxConsolidationCandidates());
        q.setMaxRelocationsPerNode(p.getMaxRelocationsPerNode());
        q.setTrendDefaultHours(p.getTrendDefaultHours());
        q.setTrendDefaultStep(p.getTrendDefaultStep());
        q.setTrendMaxPoints(p.getTrendMaxPoints());

        SchedulerProperties.Thermal t = src.getThermal();
        SchedulerProperties.Thermal u = copy.getThermal();
        u.setDefaultWarningCelsius(t.getDefaultWarningCelsius());
        u.setInertiaK(t.getInertiaK());
        u.setCooldownMinutes(t.getCooldownMinutes());

        SchedulerProperties.Network n = src.getNetwork();
        SchedulerProperties.Network m = copy.getNetwork();
        m.setLatencyScaleMs(n.getLatencyScaleMs());
        m.setDegradedPenalty(n.getDegradedPenalty());
        m.setLinkWeight(n.getLinkWeight());
        m.setUnknownLinkScore(n.getUnknownLinkScore());

        return copy;
    }
}
