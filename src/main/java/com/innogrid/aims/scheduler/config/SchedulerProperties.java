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

    /**
     * 모의 메트릭 모드 — 실제 저장소 대신 가상 클러스터({@link #syntheticClusterPath})를 본다(단계 19).
     * 1세부 데이터가 없는 동안 시연·검증용. 팀 관행({@code aims.gpu-dashboard.mock-enabled})과 같은 플래그 방식이며
     * 환경변수 {@code AIMS_SCHEDULER_MOCK_ENABLED} 로 켠다. 기본 false — 배포는 항상 꺼진 채로.
     */
    private boolean mockEnabled = false;

    /** 가상 클러스터 정의 파일. {@code classpath:} 또는 파일 경로. */
    private String syntheticClusterPath = "classpath:scheduler/synthetic-cluster.yml";

    /** 메트릭 질의 시간 상한. 저장소가 응답을 안 줄 때 스케줄러가 멈추지 않게 한다(단계 14). */
    private Metrics metrics = new Metrics();

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
    private final Dispatch dispatch = new Dispatch();

    public String getNodeJob() { return nodeJob; }
    public boolean isMockEnabled() { return mockEnabled; }
    public void setMockEnabled(boolean v) { this.mockEnabled = v; }
    public String getSyntheticClusterPath() { return syntheticClusterPath; }
    public void setSyntheticClusterPath(String v) { this.syntheticClusterPath = v; }
    public Dispatch getDispatch() { return dispatch; }
    public Metrics getMetrics() { return metrics; }
    public void setMetrics(Metrics v) { this.metrics = v; }
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

        /** 유휴 전력 비율. P_idle = P_max × idleRatio — 진단 지표·동적 범위의 기준선이고, 계측이 없을 때 깨우기 비용의 대체값 */
        private double idleRatio = 0.3;

        /**
         * 계측된 유휴 노드의 깨우기 비용을 실측 전력에서 역산하는가.
         *
         * <p>끄면 전 노드가 {@code P_max × idleRatio} 로 돌아간다 — 되돌리기는 이 값 하나다(팀 관례대로
         * 환경변수 바인딩 한 줄). 유휴 판정은 {@code isActive}(실효 CPU)와 같은 기준이고, 역산 입력은 같은 순간의
         * 실측 CPU 다. 이 값은 비교 API 의 설정 스냅샷과 다이제스트에 들어간다 — 모델 형태가 바뀌었는데
         * 다이제스트가 같으면 어제 표와 오늘 표가 같은 설정으로 보인다.</p>
         */
        private boolean idleFromMeasurement = true;

        /** PEI 정규화 기준(%/W). 이 값을 1.0 으로 본다. */
        private double peiNormalizer = 0.5;

        // ── 전력 기반 배치 점수 (PowerAwareScheduler 전용) ────────

        /**
         * 총점에서 자원 여유(CPU·메모리)가 차지하는 몫. 나머지가 전력·열이다.
         *
         * <p>정책의 가중치를 그대로 쓰지 않는 이유가 여기 있다. 정책 기본값은
         * {@code cpu .25 + memory .25 = 0.50} 대 {@code power .20} 이라, 그대로 쓰면 전력 항이
         * 소수파가 되어 전력 엔진이 전력 엔진이 아니게 된다. 그래서 전력·열 대 자원의 배분은
         * 엔진 고유값으로 두고, 정책 가중치는 <b>전력↔열·CPU↔메모리의 비율로만</b> 쓴다.</p>
         *
         * <p><b>0.10 을 쓰는 이유는 실측으로 정했다.</b> 빈 노드는 "비어 있다"는 사실 하나로
         * 자원 여유와 온도 <b>양쪽에서</b> 보상을 받는다 — 부하가 없으니 여유도 많고 온도도 낮다.
         * 전력 항 하나가 그 둘을 상대하는 구조라, 자원 몫이 0.12 이상이면 유휴 전력 150W 를
         * 새로 쓰면서까지 빈 노드를 깨우는 판단이 나온다. 0.10 이하에서 전력 판단이 이긴다.</p>
         *
         * <p>0.30 까지 올리면 기존 판단(가동 중 서버 선호)도 뒤집힌다. 반대로 0 으로 두면 자원
         * 여유를 전혀 보지 않아 한 노드에 몰리는데, 그건 사용률 임계 제약이 막는다.</p>
         */
        private double resourceShare = 0.10;

        /** 전력 점수 안에서 PEI(단위 전력당 활용률)가 차지하는 비중. */
        private double peiWeight = 0.40;

        /** 전력 점수 안에서 비낭비(이상 곡선 초과분 없음)가 차지하는 비중. */
        private double wasteWeight = 0.25;

        /** 전력 점수 안에서 실질 한계 전력(배치로 늘어나는 전력)이 차지하는 비중. */
        private double marginalWeight = 0.35;

        /**
         * 전력 곡선의 볼록 지수. P(u) = P_idle + (P_max − P_idle) × u^α
         *
         * <p><b>기본값 1.0 은 기존 선형 모델과 완전히 같다.</b> 실제 서버는 부하가 높을수록
         * 단위 부하당 전력이 더 드는 볼록 곡선에 가깝지만, 그 값을 기본으로 켜면 배치 결과가
         * 바뀐다. 기능만 넣어 두고 실험은 설정으로 한다.</p>
         *
         * <p>α&gt;1 이면 같은 워크로드라도 이미 바쁜 노드에 얹을 때 ΔP 가 커진다. 이것이
         * 통합(유휴 서버 반납)과 맞서는 지점이다.</p>
         */
        private double curveAlpha = 1.0;

        /**
         * 노드를 "가동 중"으로 볼 CPU 사용률(%) 기준.
         *
         * <p>이 값 이하면 사실상 빈 노드로 보고, 여기에 배치하면 <b>유휴 전력이 새로 드는 것</b>
         * 으로 계산한다. 클러스터 총 전력은 "활성 노드의 유휴 전력 + 동적 전력"의 합이므로,
         * 노드를 새로 깨우는 비용이 곧 그 노드의 유휴 전력이다. 이 한 줄이 전력 통합을 임의의
         * 보너스가 아니라 물리량으로 만든다.</p>
         *
         * <p>5% 는 OS·에이전트만 도는 상태와 실제 워크로드가 도는 상태를 가르는 값이다.</p>
         */
        private double activeCpuPercent = 5.0;

        /**
         * 실질 한계 전력 점수의 분모가 되는 <b>W 눈금</b>.
         *
         * <p>깨우기 벌점을 노드 정격으로 나누면 유휴비로 상대화되어, 절대 W 가 다른 노드가 같은
         * 벌점을 받는다. 고정 W 로 나누면 벌점이 절대 W 로 총점에 들어간다.</p>
         *
         * <p>기본 500 W 는 (i) 정격 500 W 노드의 점수를 그대로 두고 (ii) 비교 판 실측 최댓값
         * 316 W 를 넘어 점수가 0 으로 포화하지 않게 하는 값이다. <b>0 이하면 정격 분모(구 방식)</b>
         * 로 돌아간다 — 되돌리기는 이 값 하나다.</p>
         *
         * <p>가중치와 마찬가지로 <b>바뀔 수 있는 값</b>이다. 바꾸면 배치 결정과 이력에 저장되는
         * 점수의 눈금이 새 행부터 달라지므로, 비교 응답의 configDigest 에 성분으로 실어 둔다.</p>
         */
        private double marginalScaleWatts = 500.0;

        // ── 통합 가능성 진단 상한 ──────────────────────────────────

        /**
         * 통합 가능성을 따져볼 후보 노드 수 상한.
         *
         * <p>판정은 노드마다 워크로드를 실제로 재배치해 보는 방식이라, 총 채점 횟수가
         * <b>후보 노드 수 × 워크로드 수 × 클러스터 노드 수</b>로 늘어난다. 상한 10 · 50 에
         * 50노드 클러스터라도 25,000회 남짓이고 DB·네트워크를 타지 않아 100ms 안쪽이다.
         * 성능 방어선이라기보다 <b>폭주 방지용</b>이다.</p>
         *
         * <p>후보는 절감 잠재량(유휴 전력)이 큰 순서로 돈다. 상한에 걸려도 가장 가치 있는
         * 후보는 이미 평가된 상태가 된다.</p>
         */
        private int maxConsolidationCandidates = 10;

        /**
         * 노드 하나에서 옮겨볼 워크로드 수 상한.
         *
         * <p><b>이 값을 넘는 노드는 반납 가능으로 판정하지 않는다.</b> 60건 중 50건만 옮겨보고
         * "반납 가능"이라 하면 거짓이다 — 남은 10건이 그 노드를 붙잡고 있다. 평가하지 않았다는
         * 사실을 사유와 함께 돌려준다.</p>
         */
        private int maxRelocationsPerNode = 50;

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
        public boolean isIdleFromMeasurement() { return idleFromMeasurement; }
        public void setIdleFromMeasurement(boolean v) { this.idleFromMeasurement = v; }
        public double getPeiNormalizer() { return peiNormalizer; }
        public void setPeiNormalizer(double v) { this.peiNormalizer = v; }
        public double getResourceShare() { return resourceShare; }
        public void setResourceShare(double v) { this.resourceShare = v; }
        public double getPeiWeight() { return peiWeight; }
        public void setPeiWeight(double v) { this.peiWeight = v; }
        public double getWasteWeight() { return wasteWeight; }
        public void setWasteWeight(double v) { this.wasteWeight = v; }
        public double getMarginalWeight() { return marginalWeight; }
        public void setMarginalWeight(double v) { this.marginalWeight = v; }
        public double getCurveAlpha() { return curveAlpha; }
        public void setCurveAlpha(double v) { this.curveAlpha = v; }
        public double getActiveCpuPercent() { return activeCpuPercent; }
        public void setActiveCpuPercent(double v) { this.activeCpuPercent = v; }
        public double getMarginalScaleWatts() { return marginalScaleWatts; }
        public void setMarginalScaleWatts(double v) { this.marginalScaleWatts = v; }
        public int getMaxConsolidationCandidates() { return maxConsolidationCandidates; }
        public void setMaxConsolidationCandidates(int v) { this.maxConsolidationCandidates = v; }
        public int getMaxRelocationsPerNode() { return maxRelocationsPerNode; }
        public void setMaxRelocationsPerNode(int v) { this.maxRelocationsPerNode = v; }
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

        /**
         * 네트워크 점수에서 링크 점수가 차지하는 비중(0~1). 나머지는 노드 트래픽 점수.
         * 게이트(정책 affinityEnabled + 워크로드 dataMdcId)가 켜진 배치에서만 쓰인다.
         */
        private double linkWeight = 0.5;

        public double getLatencyScaleMs() { return latencyScaleMs; }
        public void setLatencyScaleMs(double v) { this.latencyScaleMs = v; }
        public double getDegradedPenalty() { return degradedPenalty; }
        public void setDegradedPenalty(double v) { this.degradedPenalty = v; }
        public double getLinkWeight() { return linkWeight; }
        public void setLinkWeight(double v) { this.linkWeight = v; }
    }

    /**
     * 메트릭 질의 시간 상한 — 저장소 클라이언트에 타임아웃이 없어(타 담당자 파일) 스케줄러 쪽에서 기다리기를 멈춘다.
     *
     * <p>기다리기를 멈춰도 HTTP 호출 자체는 저장소가 응답할 때까지 풀 스레드를 붙잡는다. 그래서 풀은 고정 크기이고,
     * 붙잡힌 스레드를 실제로 회수하는 것은 클라이언트 타임아웃(12장 T8)만 할 수 있다.</p>
     */
    public static class Metrics {

        /** 노드 한 대의 질의(핵심 4 + 부가 6) 전체 상한(ms). 넘으면 그 노드를 "응답 없음"으로 제외한다. */
        private long nodeTimeoutMs = 10_000;

        /** 스냅샷 전체 상한(ms). 넘으면 끝나지 않은 노드를 전부 제외한다. */
        private long collectTimeoutMs = 30_000;

        /** 단건 질의(대상 노드 목록·GPU 개수·전력 효율 추세) 상한(ms). */
        private long queryTimeoutMs = 5_000;

        /** 내부 풀 크기 = 저장소 동시 질의 수. */
        private int parallelism = 4;

        public long getNodeTimeoutMs() { return nodeTimeoutMs; }
        public void setNodeTimeoutMs(long v) { this.nodeTimeoutMs = v; }
        public long getCollectTimeoutMs() { return collectTimeoutMs; }
        public void setCollectTimeoutMs(long v) { this.collectTimeoutMs = v; }
        public long getQueryTimeoutMs() { return queryTimeoutMs; }
        public void setQueryTimeoutMs(long v) { this.queryTimeoutMs = v; }
        public int getParallelism() { return parallelism; }
        public void setParallelism(int v) { this.parallelism = v; }
    }

    /**
     * 실행 계층 연동(디스패치) — 배치 결정을 실행 계층으로 넘기는 방식.
     *
     * <p>{@code mode} 는 등록된 디스패처 식별자다. 기본 {@code record} 는 외부를 부르지 않고 기록만 한다(실행 API 미정).
     * {@code timeoutMinutes} 는 원격 모드에서 응답 없는 요청을 TIMED_OUT 으로 볼 시간이며 record 모드에서는 쓰지 않는다.</p>
     */
    public static class Dispatch {

        private String mode = "record";
        private int timeoutMinutes = 30;

        public String getMode() { return mode; }
        public void setMode(String v) { this.mode = v; }
        public int getTimeoutMinutes() { return timeoutMinutes; }
        public void setTimeoutMinutes(int v) { this.timeoutMinutes = v; }
    }
}
