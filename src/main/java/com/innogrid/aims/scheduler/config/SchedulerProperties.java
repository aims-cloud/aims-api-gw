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

    /**
     * 배치한 워크로드가 메트릭에 나타나기까지 걸리는 시간(분).
     *
     * <p>실행 중(RUNNING)인 워크로드는 이미 노드 사용률 메트릭에 포함돼 있다. 그것을 예약으로
     * 또 잡으면 한 워크로드가 두 번 계산된다. 반대로 방금 시작한 워크로드는 아직 메트릭에
     * 잡히지 않아 예약으로 봐야 한다. 그 경계가 이 값이다.</p>
     *
     * <p>기본 10분은 Prometheus 스크레이프 주기와 수집 쿼리의 {@code irate(...[5m])} 창을
     * 함께 덮는 값이다. 이 값을 줄이면 갓 시작한 워크로드의 자원이 잠시 이중으로 비어
     * 보이고, 늘리면 이중 계상 구간이 길어진다.</p>
     */
    private int metricLagMinutes = 10;

    /**
     * <b>스케줄 cron 을 해석할 시간대.</b>
     *
     * <p>배포 VM 은 OS·DB 모두 {@code Etc/UTC} 이고 TZ 환경변수도 없어 JVM 기본 시간대가 UTC 다.
     * 그대로 두면 화면에서 "매일 02:00" 으로 만든 스케줄이 한국 시간 오전 11시에 실행된다.
     * cron 문자열에는 시간대 정보가 없으므로 <b>입력을 한국 시간(UTC+9)으로 가정</b>해 해석한다.</p>
     *
     * <p>이 값은 <b>cron 해석에만</b> 쓴다. 저장·응답 시각은 전부 UTC {@code OffsetDateTime} 이고
     * 표출 변환은 프런트가 한다. JVM 전역 시간대는 바꾸지 않는다 — 그러면 미터링 집계·알람 평가 등
     * 다른 담당자의 기능까지 함께 움직인다.</p>
     *
     * <p>Asia/Seoul 은 서머타임이 없어 존재하지 않는 시각·중복 시각 문제가 발생하지 않는다.
     * 이 값을 바꾸면 기존 스케줄의 실행 시각이 전부 이동하므로 운영 공지가 필요하다.</p>
     */
    private String timezone = "Asia/Seoul";

    /** 자동 실행 대상을 확인하는 주기(ms). 이 값보다 짧은 주기의 cron 은 등록 시 거부한다. */
    private long pollIntervalMs = 10_000;

    /**
     * 러너가 첫 폴링을 시작하기까지 기다리는 시간(초).
     * DB·외부 연동이 준비되기 전에 실행되지 않게 하고, 테스트 컨텍스트에서 폴링이 돌지 않게 한다.
     */
    private int initialDelaySeconds = 30;

    /** 실행 이력 보존 기간(일). 러너가 하루 한 번 오래된 행을 지운다. */
    private int runRetentionDays = 90;

    /** 한 번의 실행에서 처리할 워크로드 상한. 초과분은 다음 실행으로 넘긴다. */
    private int maxWorkloadsPerRun = 500;

    /**
     * FAILED 워크로드를 다시 배치 시도할 최대 횟수.
     * 영영 배치할 수 없는 워크로드가 매 실행마다 재시도되어 이력을 채우는 것을 막는다.
     */
    private int maxRetryCount = 5;

    private final Power power = new Power();
    private final Thermal thermal = new Thermal();
    private final Network network = new Network();

    public String getNodeJob() { return nodeJob; }
    public void setNodeJob(String nodeJob) { this.nodeJob = nodeJob; }
    public String getDiskMountPoint() { return diskMountPoint; }
    public void setDiskMountPoint(String diskMountPoint) { this.diskMountPoint = diskMountPoint; }
    public int getReservationTtlHours() { return reservationTtlHours; }
    public void setReservationTtlHours(int reservationTtlHours) { this.reservationTtlHours = reservationTtlHours; }
    public int getMetricLagMinutes() { return metricLagMinutes; }
    public void setMetricLagMinutes(int metricLagMinutes) { this.metricLagMinutes = metricLagMinutes; }
    public String getTimezone() { return timezone; }
    public void setTimezone(String timezone) { this.timezone = timezone; }
    public long getPollIntervalMs() { return pollIntervalMs; }
    public void setPollIntervalMs(long pollIntervalMs) { this.pollIntervalMs = pollIntervalMs; }
    public int getInitialDelaySeconds() { return initialDelaySeconds; }
    public void setInitialDelaySeconds(int initialDelaySeconds) { this.initialDelaySeconds = initialDelaySeconds; }
    public int getRunRetentionDays() { return runRetentionDays; }
    public void setRunRetentionDays(int runRetentionDays) { this.runRetentionDays = runRetentionDays; }
    public int getMaxWorkloadsPerRun() { return maxWorkloadsPerRun; }
    public void setMaxWorkloadsPerRun(int maxWorkloadsPerRun) { this.maxWorkloadsPerRun = maxWorkloadsPerRun; }
    public int getMaxRetryCount() { return maxRetryCount; }
    public void setMaxRetryCount(int maxRetryCount) { this.maxRetryCount = maxRetryCount; }
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

        /** 전력 효율 추세의 기본 조회 구간(시간). from·to 를 주지 않으면 최근 이만큼을 본다. */
        private int trendDefaultHours = 24;

        /** 전력 효율 추세의 기본 간격. */
        private String trendDefaultStep = "5m";

        /**
         * 추세 한 번에 돌려줄 최대 지점 수.
         *
         * <p>메트릭 저장소 클라이언트에 읽기 타임아웃이 걸려 있지 않아, 넓은 구간을 잘게
         * 잘라 물으면 요청 스레드가 오래 붙잡힌다. 구간 ÷ 간격이 이 값을 넘으면 거절해
         * 애초에 무거운 질의가 나가지 않게 한다.</p>
         */
        private int trendMaxPoints = 2000;

        public double getDefaultCapacityWatts() { return defaultCapacityWatts; }
        public void setDefaultCapacityWatts(double v) { this.defaultCapacityWatts = v; }
        public Map<String, Double> getCapacityByNode() { return capacityByNode; }
        public void setCapacityByNode(Map<String, Double> v) { this.capacityByNode = v; }
        public double getIdleRatio() { return idleRatio; }
        public void setIdleRatio(double v) { this.idleRatio = v; }
        public double getPeiNormalizer() { return peiNormalizer; }
        public void setPeiNormalizer(double v) { this.peiNormalizer = v; }
        public int getTrendDefaultHours() { return trendDefaultHours; }
        public void setTrendDefaultHours(int v) { this.trendDefaultHours = v; }
        public String getTrendDefaultStep() { return trendDefaultStep; }
        public void setTrendDefaultStep(String v) { this.trendDefaultStep = v; }
        public int getTrendMaxPoints() { return trendMaxPoints; }
        public void setTrendMaxPoints(int v) { this.trendMaxPoints = v; }
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
