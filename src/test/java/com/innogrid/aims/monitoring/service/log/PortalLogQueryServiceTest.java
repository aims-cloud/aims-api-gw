package com.innogrid.aims.monitoring.service.log;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;

import com.innogrid.aims.common.dto.PageResponse;
import com.innogrid.aims.common.exception.BusinessException;
import com.innogrid.aims.monitoring.dto.log.PortalLogDetailResponse;
import com.innogrid.aims.monitoring.dto.log.PortalLogListItemResponse;
import com.innogrid.aims.monitoring.dto.log.PortalLogResult;
import com.innogrid.aims.monitoring.dto.log.PortalLogType;
import com.innogrid.aims.monitoring.entity.PortalLogEntry;
import com.innogrid.aims.monitoring.repository.PortalLogRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 로그 관리 조회 — 날짜 기간 변환(양끝 포함·KST), 필터·정렬·페이징, 잘못된 정렬/기간 400, 상세 404.
 * 실제 H2 리포지토리를 쓰고 서비스만 KST 고정으로 만든다.
 *
 * 대역 (KST): 09-05 23:30 AUTH / 09-06 09:00 AUDIT / 09-06 23:59 AUDIT FAILURE / 09-07 00:00 SYSTEM / 09-07 12:00 ACTION
 */
@SpringBootTest
@Transactional
class PortalLogQueryServiceTest {

    static final ZoneId KST = ZoneId.of("Asia/Seoul");
    static final OffsetDateTime D6 = OffsetDateTime.of(2026, 9, 6, 0, 0, 0, 0, ZoneOffset.ofHours(9));

    @Autowired
    PortalLogRepository repository;
    PortalLogQueryService service;
    Long detailId;

    @BeforeEach
    void setUp() {
        repository.deleteAll();
        service = new PortalLogQueryService(repository, KST);
        repository.save(PortalLogEntry.builder(PortalLogType.AUTH).startedAt(D6.minusMinutes(30))
                .user("admin@innogrid.com", "관리자").screen("로그인").action("로그인").content("로그인 성공").build());
        detailId = repository.save(PortalLogEntry.builder(PortalLogType.AUDIT).startedAt(D6.plusHours(9)).completedAt(D6.plusHours(9).plusSeconds(1))
                .user("admin@innogrid.com", "관리자").clientIp("10.0.0.9").requestId("req-9").request("POST", "/api/v1/network-zones")
                .screen("네트워크 구역").action("생성").content("네트워크 구역 생성: zone-a").build()).getId();
        repository.save(PortalLogEntry.builder(PortalLogType.AUDIT).startedAt(D6.plusHours(23).plusMinutes(59))
                .user("gildong@innogrid.com", "홍길동").screen("알람 정책").action("수정").content("정책 수정: cpu-high")
                .result(PortalLogResult.FAILURE).description("정책을 찾을 수 없습니다").build());
        repository.save(PortalLogEntry.builder(PortalLogType.SYSTEM).startedAt(D6.plusDays(1))
                .screen("수집").action("연동 실패").content("Tumblebug 429").result(PortalLogResult.FAILURE).build());
        repository.save(PortalLogEntry.builder(PortalLogType.ACTION).startedAt(D6.plusDays(1).plusHours(12))
                .user("admin@innogrid.com", "관리자").screen("가상머신").action("생성").content("Infra 배포")
                .result(PortalLogResult.IN_PROGRESS).ref("INFRA_JOB", "job-1").build());
    }

    @Test
    void 기본_조회는_시작_시각_내림차순_1페이지_10건이다() {
        PageResponse<PortalLogListItemResponse> page = service.getLogs(null, null, null, null, null, null, 1, 10, "startedAt,desc");

        assertThat(page.totalElements()).isEqualTo(5);
        assertThat(page.page()).isEqualTo(1);
        assertThat(page.items()).extracting(PortalLogListItemResponse::type)
                .containsExactly(PortalLogType.ACTION, PortalLogType.SYSTEM, PortalLogType.AUDIT, PortalLogType.AUDIT, PortalLogType.AUTH);
        assertThat(page.items().get(1).userName()).isNull();                 // SYSTEM 은 사용자 없음 → 화면 "시스템"
    }

    @Test
    void 날짜_기간은_KST_양끝_포함으로_변환된다() {
        // 09-06 하루: 09:00 AUDIT, 23:59 AUDIT — 09-05 23:30 과 09-07 00:00 은 제외
        PageResponse<PortalLogListItemResponse> day = service.getLogs(null, null,
                LocalDate.of(2026, 9, 6), LocalDate.of(2026, 9, 6), null, null, 1, 10, null);
        assertThat(day.totalElements()).isEqualTo(2);
        assertThat(day.items()).allSatisfy(i -> assertThat(i.type()).isEqualTo(PortalLogType.AUDIT));

        // to 만 지정 — 09-05 까지
        assertThat(service.getLogs(null, null, null, LocalDate.of(2026, 9, 5), null, null, 1, 10, null).totalElements()).isEqualTo(1);
        // from 만 지정 — 09-07 부터
        assertThat(service.getLogs(null, null, LocalDate.of(2026, 9, 7), null, null, null, 1, 10, null).totalElements()).isEqualTo(2);
    }

    @Test
    void 타입_키워드_사용자_결과_필터와_정렬_페이징() {
        assertThat(service.getLogs(List.of(PortalLogType.AUDIT, PortalLogType.AUTH), null, null, null, null, null, 1, 10, null)
                .totalElements()).isEqualTo(3);
        assertThat(service.getLogs(null, "정책", null, null, null, null, 1, 10, null).items())
                .extracting(PortalLogListItemResponse::content).containsExactly("정책 수정: cpu-high");
        assertThat(service.getLogs(null, null, null, null, "admin@innogrid.com", null, 1, 10, null).totalElements()).isEqualTo(3);
        assertThat(service.getLogs(null, null, null, null, null, PortalLogResult.FAILURE, 1, 10, null).totalElements()).isEqualTo(2);

        PageResponse<PortalLogListItemResponse> byUser = service.getLogs(null, null, null, null, null, null, 2, 2, "userName,asc");
        assertThat(byUser.totalPages()).isEqualTo(3);
        assertThat(byUser.items()).hasSize(2);

        PageResponse<PortalLogListItemResponse> asc = service.getLogs(null, null, null, null, null, null, 1, 1, "startedAt,asc");
        assertThat(asc.items().get(0).type()).isEqualTo(PortalLogType.AUTH);
    }

    @Test
    void 허용하지_않는_정렬_필드와_뒤집힌_기간은_400이다() {
        assertThatThrownBy(() -> service.getLogs(null, null, null, null, null, null, 1, 10, "clientIp,asc"))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getStatus()).isEqualTo(HttpStatus.BAD_REQUEST));
        assertThatThrownBy(() -> service.getLogs(null, null, LocalDate.of(2026, 9, 7), LocalDate.of(2026, 9, 6), null, null, 1, 10, null))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getStatus()).isEqualTo(HttpStatus.BAD_REQUEST));
    }

    @Test
    void 상세는_접속_IP와_요청_추적_정보를_포함하고_없으면_404다() {
        PortalLogDetailResponse detail = service.getLog(detailId);

        assertThat(detail.userId()).isEqualTo("admin@innogrid.com");
        assertThat(detail.userName()).isEqualTo("관리자");
        assertThat(detail.clientIp()).isEqualTo("10.0.0.9");
        assertThat(detail.requestId()).isEqualTo("req-9");
        assertThat(detail.httpMethod()).isEqualTo("POST");
        assertThat(detail.requestUri()).isEqualTo("/api/v1/network-zones");
        assertThat(detail.completedAt()).isNotNull();

        assertThatThrownBy(() -> service.getLog(999_999L))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getStatus()).isEqualTo(HttpStatus.NOT_FOUND));
    }
}
