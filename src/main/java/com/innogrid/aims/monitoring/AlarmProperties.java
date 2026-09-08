package com.innogrid.aims.monitoring;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** 알람 설정 (prefix: aims.alarm) — docs/09 A.6 #6 평가 주기, D.1 평가기. */
@Component
@ConfigurationProperties(prefix = "aims.alarm")
public class AlarmProperties {

    private final Evaluation evaluation = new Evaluation();

    public Evaluation getEvaluation() { return evaluation; }

    /** 평가기 */
    public static class Evaluation {

        /**
         * 평가 주기(초). 메트릭당 Thanos 즉시 쿼리 1회가 이 주기마다 나간다. 기본 60초.
         * {@code @Scheduled} 는 기동 시 고정되므로 변경은 재기동 후 반영된다.
         */
        private int intervalSeconds = 60;

        /**
         * rate() 창. 스크레이프 누락에 견디려면 스크레이프 간격의 4배 이상이어야 하고, 자원 탐색기 패널의 rate 창 하한(5분)과 같아야
         * 화면 수치와 알람 판정이 일치한다. 기본 5분.
         */
        private Duration rateWindow = Duration.ofMinutes(5);

        public int getIntervalSeconds() { return intervalSeconds; }
        public void setIntervalSeconds(int intervalSeconds) { this.intervalSeconds = intervalSeconds; }

        public Duration getRateWindow() { return rateWindow; }
        public void setRateWindow(Duration rateWindow) { this.rateWindow = rateWindow; }

        /** PromQL 범위 표기 — 분 단위로 나누어지면 {@code 5m}, 아니면 {@code 90s} */
        public String rateWindowPromql() {
            long seconds = Math.max(1, rateWindow.toSeconds());
            return seconds % 60 == 0 ? (seconds / 60) + "m" : seconds + "s";
        }
    }
}
