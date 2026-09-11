package com.innogrid.aims.monitoring.log;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** 포털 로그 설정 (prefix: aims.portal-log) — docs/08 §9 보존. */
@Component
@ConfigurationProperties(prefix = "aims.portal-log")
public class PortalLogProperties {

    private final Retention retention = new Retention();

    public Retention getRetention() { return retention; }

    /** 보존 배치 — 기본 1년, SYSTEM 90일. {@code @Scheduled} cron 은 기동 시 고정되어 변경은 재기동 후 반영된다 */
    public static class Retention {

        /** 배치 실행 여부 */
        private boolean enabled = true;

        /** SYSTEM 외 타입 보존 일수. 기본 365 */
        private int defaultDays = 365;

        /** SYSTEM 타입 보존 일수. 기본 90 */
        private int systemDays = 90;

        /** 한 트랜잭션에서 지우는 최대 행 수 — 락 유지 시간을 짧게 */
        private int batchSize = 5000;

        /** 한 번 실행에서 도는 최대 배치 수 — 첫 실행처럼 적체가 클 때 무한히 돌지 않게. 남은 건 다음 실행에서 */
        private int maxBatchesPerRun = 200;

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }

        public int getDefaultDays() { return defaultDays; }
        public void setDefaultDays(int defaultDays) { this.defaultDays = defaultDays; }

        public int getSystemDays() { return systemDays; }
        public void setSystemDays(int systemDays) { this.systemDays = systemDays; }

        public int getBatchSize() { return batchSize; }
        public void setBatchSize(int batchSize) { this.batchSize = batchSize; }

        public int getMaxBatchesPerRun() { return maxBatchesPerRun; }
        public void setMaxBatchesPerRun(int maxBatchesPerRun) { this.maxBatchesPerRun = maxBatchesPerRun; }
    }
}
