package com.innogrid.aims.monitoring;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** 알람 설정 (prefix: aims.alarm) — docs/09 A.6 #6 평가 주기, D.1 평가기, A.5.4 전송. */
@Component
@ConfigurationProperties(prefix = "aims.alarm")
public class AlarmProperties {

    private final Evaluation evaluation = new Evaluation();
    private final Notification notification = new Notification();

    public Evaluation getEvaluation() { return evaluation; }
    public Notification getNotification() { return notification; }

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

    /** 전송 (docs/09 A.5.4) — 메일 서버 자체는 {@code spring.mail.*}(환경 변수 SPRING_MAIL_*)로 받는다 */
    public static class Notification {

        /** 전송 파이프라인 사용 여부. false 면 발생 알람을 전송 원장에 기록하지 않는다(평가·이력은 그대로) */
        private boolean enabled = true;

        /** 발신자(From) 주소. 비우면 메일 서버 계정 기본값 */
        private String from;

        /** 제목 접두 — 예: "[AIMS]" */
        private String subjectPrefix = "[AIMS]";

        /** 수신자 1명당 최대 발송 시도 횟수 */
        private int maxAttempts = 3;

        /** 재시도 사이 대기 — 시도 횟수를 곱해 늘린다(2s, 4s, …) */
        private Duration retryBackoff = Duration.ofSeconds(2);

        /** 발송 전용 스레드 수 — 평가기 스케줄러 스레드와 분리 */
        private int threads = 2;

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }

        public String getFrom() { return from; }
        public void setFrom(String from) { this.from = from; }

        public String getSubjectPrefix() { return subjectPrefix; }
        public void setSubjectPrefix(String subjectPrefix) { this.subjectPrefix = subjectPrefix; }

        public int getMaxAttempts() { return maxAttempts; }
        public void setMaxAttempts(int maxAttempts) { this.maxAttempts = maxAttempts; }

        public Duration getRetryBackoff() { return retryBackoff; }
        public void setRetryBackoff(Duration retryBackoff) { this.retryBackoff = retryBackoff; }

        public int getThreads() { return threads; }
        public void setThreads(int threads) { this.threads = threads; }
    }
}
