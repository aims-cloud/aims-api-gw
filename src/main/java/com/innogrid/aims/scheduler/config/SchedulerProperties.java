package com.innogrid.aims.scheduler.config;

import java.util.HashMap;
import java.util.Map;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 스케줄러 설정.
 *
 * 모든 값에 코드 기본값을 두어 application.yml 을 수정하지 않아도 동작한다.
 * 환경별로 바꾸려면 환경변수(AIMS_SCHEDULER_*)로만 덮어쓴다.
 */
@Component
@ConfigurationProperties(prefix = "aims.scheduler")
public class SchedulerProperties {

    /** 노드 수집 대상 Prometheus job 이름. */
    private String nodeJob = "mdc-nodes";

    /** 디스크 사용률을 읽을 마운트 포인트. 여러 시리즈 중 하나를 고정하기 위해 사용한다. */
    private String diskMountPoint = "/";

    /**
     * 예약(배치됐지만 아직 실행 상태로 전이되지 않은 워크로드)을 유효하게 볼 시간.
     * 이 시간을 넘긴 SCHEDULED 워크로드는 예약 집계에서 제외해 자원이 영구히 묶이지 않게 한다.
     */
    private int reservationTtlHours = 24;

    private final Power power = new Power();
    private final Thermal thermal = new Thermal();
    private final Network network = new Network();

    public String getNodeJob() { return nodeJob; }
    public void setNodeJob(String nodeJob) { this.nodeJob = nodeJob; }
    public String getDiskMountPoint() { return diskMountPoint; }
    public void setDiskMountPoint(String diskMountPoint) { this.diskMountPoint = diskMountPoint; }
    public int getReservationTtlHours() { return reservationTtlHours; }
    public void setReservationTtlHours(int reservationTtlHours) { this.reservationTtlHours = reservationTtlHours; }
    public Power getPower() { return power; }
    public Thermal getThermal() { return thermal; }
    public Network getNetwork() { return network; }

    /** 전력 모델 설정. */
    public static class Power {

        /**
         * 노드 전력 용량 기본값(W).
         * IPMI 에서 정격 전력을 얻을 수 없으므로 설정으로 둔다.
         * 기존 코드가 쓰던 500W 를 그대로 기본값으로 사용해 동작을 보존한다.
         */
        private double defaultCapacityWatts = 500.0;

        /** 노드별 전력 용량 override. key 는 노드 식별자(instance). */
        private Map<String, Double> capacityByNode = new HashMap<>();

        /** 유휴 전력 비율. P_idle = P_max × idleRatio */
        private double idleRatio = 0.3;

        /** PEI 정규화 기준(%/W). 이 값을 1.0 으로 본다. */
        private double peiNormalizer = 0.5;

        public double getDefaultCapacityWatts() { return defaultCapacityWatts; }
        public void setDefaultCapacityWatts(double v) { this.defaultCapacityWatts = v; }
        public Map<String, Double> getCapacityByNode() { return capacityByNode; }
        public void setCapacityByNode(Map<String, Double> v) { this.capacityByNode = v; }
        public double getIdleRatio() { return idleRatio; }
        public void setIdleRatio(double v) { this.idleRatio = v; }
        public double getPeiNormalizer() { return peiNormalizer; }
        public void setPeiNormalizer(double v) { this.peiNormalizer = v; }
    }

    /** 온도 모델 설정. */
    public static class Thermal {

        /**
         * 정책에 경고 온도가 지정되지 않았을 때 사용할 기본 경고 온도(℃).
         * 경고 구간에서는 배치를 막지 않고 점수만 낮춘다.
         */
        private double defaultWarningCelsius = 65.0;

        /** 열관성 계수. ΔT = k × (요구 CPU / 전체 CPU) × 현재 온도 */
        private double inertiaK = 0.15;

        /** 임계 온도를 넘긴 노드를 배치 대상에서 제외하는 시간(분). */
        private int cooldownMinutes = 10;

        public double getDefaultWarningCelsius() { return defaultWarningCelsius; }
        public void setDefaultWarningCelsius(double v) { this.defaultWarningCelsius = v; }
        public double getInertiaK() { return inertiaK; }
        public void setInertiaK(double v) { this.inertiaK = v; }
        public int getCooldownMinutes() { return cooldownMinutes; }
        public void setCooldownMinutes(int v) { this.cooldownMinutes = v; }
    }

    /** 네트워크 모델 설정. */
    public static class Network {

        /** 링크 지연 정규화 기준(ms). 이 값을 비용 1.0 으로 본다. */
        private double latencyScaleMs = 50.0;

        /** DEGRADED 링크에 더할 비용. */
        private double degradedPenalty = 0.3;

        public double getLatencyScaleMs() { return latencyScaleMs; }
        public void setLatencyScaleMs(double v) { this.latencyScaleMs = v; }
        public double getDegradedPenalty() { return degradedPenalty; }
        public void setDegradedPenalty(double v) { this.degradedPenalty = v; }
    }
}
