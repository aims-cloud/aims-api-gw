package com.innogrid.aims.monitoring.repository;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

import com.innogrid.aims.monitoring.dto.log.PortalLogResult;
import com.innogrid.aims.monitoring.dto.log.PortalLogType;
import com.innogrid.aims.monitoring.entity.PortalLogEntry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 포털 로그 원장 — 타입·키워드(5컬럼)·기간·사용자·결과 필터, 정렬·페이징, 길이 초과 절단, ACTION 진행중 행 조회.
 *
 * 대역: 2026-09-07 KST 기준 5건
 *   09:00 AUDIT  관리자(superadmin)   네트워크 구역/생성  SUCCESS
 *   09:30 AUDIT  홍길동(gildong_hong) 알람 정책/수정      FAILURE  "정책을 찾을 수 없습니다"
 *   10:00 AUTH   관리자(superadmin)   로그인/로그인       SUCCESS
 *   11:00 SYSTEM (시스템)             수집/연동 실패      FAILURE  Tumblebug 429
 *   12:00 ACTION 관리자(superadmin)   가상머신/생성       IN_PROGRESS ref INFRA_JOB/job-1
 */
@SpringBootTest
@Transactional
class PortalLogRepositoryTest {

    @Autowired
    PortalLogRepository repository;

    static final OffsetDateTime BASE = OffsetDateTime.of(2026, 9, 7, 9, 0, 0, 0, ZoneOffset.ofHours(9));

    @BeforeEach
    void setUp() {
        repository.deleteAll();
        repository.save(PortalLogEntry.builder(PortalLogType.AUDIT).startedAt(BASE).completedAt(BASE.plusSeconds(1))
                .user("superadmin", "관리자").clientIp("192.168.130.251")
                .screen("네트워크 구역").action("생성").content("네트워크 구역 생성: zone-a")
                .request("POST", "/api/v1/network-zones").requestId("req-1").build());
        repository.save(PortalLogEntry.builder(PortalLogType.AUDIT).startedAt(BASE.plusMinutes(30))
                .user("gildong_hong", "홍길동").screen("알람 정책").action("수정").content("정책 수정: cpu-high")
                .result(PortalLogResult.FAILURE).description("정책을 찾을 수 없습니다: cpu-high").build());
        repository.save(PortalLogEntry.builder(PortalLogType.AUTH).startedAt(BASE.plusHours(1))
                .user("superadmin", "관리자").screen("로그인").action("로그인").content("로그인 성공").build());
        repository.save(PortalLogEntry.builder(PortalLogType.SYSTEM).startedAt(BASE.plusHours(2))
                .screen("수집").action("연동 실패").content("Tumblebug 429 rate limit (ns=openstack)")
                .result(PortalLogResult.FAILURE).description("rate limit exceeded").build());
        repository.save(PortalLogEntry.builder(PortalLogType.ACTION).startedAt(BASE.plusHours(3))
                .user("superadmin", "관리자").screen("가상머신").action("생성").content("Infra 배포: openstack-infra-01")
                .result(PortalLogResult.IN_PROGRESS).ref("INFRA_JOB", "job-1").build());
    }

    @Test
    void 필터_없이_조회하면_전체가_시작_시각_내림차순으로_나온다() {
        Page<PortalLogEntry> page = repository.findAll(
                PortalLogSpecifications.filter(null, null, null, null, null, null),
                PageRequest.of(0, 10, Sort.by(Sort.Direction.DESC, "startedAt")));

        assertThat(page.getTotalElements()).isEqualTo(5);
        assertThat(page.getContent()).extracting(PortalLogEntry::getType)
                .containsExactly(PortalLogType.ACTION, PortalLogType.SYSTEM, PortalLogType.AUTH,
                        PortalLogType.AUDIT, PortalLogType.AUDIT);
        assertThat(page.getContent().get(0).getCreatedAt()).isNotNull();
    }

    @Test
    void 타입_다중_필터와_페이징() {
        Page<PortalLogEntry> first = repository.findAll(
                PortalLogSpecifications.filter(List.of(PortalLogType.AUDIT, PortalLogType.AUTH), null, null, null, null, null),
                PageRequest.of(0, 2, Sort.by(Sort.Direction.DESC, "startedAt")));

        assertThat(first.getTotalElements()).isEqualTo(3);
        assertThat(first.getTotalPages()).isEqualTo(2);
        assertThat(first.getContent()).extracting(PortalLogEntry::getType)
                .containsExactly(PortalLogType.AUTH, PortalLogType.AUDIT);
    }

    @Test
    void 키워드는_5개_컬럼에서_대소문자_무시_부분_일치로_찾고_userId와_IP는_제외한다() {
        assertThat(search("ZONE-A")).extracting(PortalLogEntry::getContent).containsExactly("네트워크 구역 생성: zone-a");
        assertThat(search("홍길동")).hasSize(1);                                       // user_name
        assertThat(search("알람")).hasSize(1);                                         // screen
        assertThat(search("연동 실패")).hasSize(1);                                    // action
        assertThat(search("찾을 수 없습니다")).hasSize(1);                             // description
        assertThat(search("superadmin")).isEmpty();                                   // user_id 는 검색 대상 아님
        assertThat(search("192.168")).isEmpty();                                      // client_ip 도 아님
        assertThat(search("100%")).isEmpty();                                         // 와일드카드는 리터럴
    }

    @Test
    void 기간은_시작_포함_끝_미포함이고_한쪽만_지정할_수_있다() {
        List<PortalLogEntry> inRange = repository.findAll(PortalLogSpecifications.filter(
                null, null, BASE.plusMinutes(30), BASE.plusHours(2), null, null));
        assertThat(inRange).extracting(PortalLogEntry::getType)
                .containsExactlyInAnyOrder(PortalLogType.AUDIT, PortalLogType.AUTH);   // 09:30, 10:00 (11:00 은 미포함)

        List<PortalLogEntry> openStart = repository.findAll(PortalLogSpecifications.filter(
                null, null, null, BASE.plusMinutes(1), null, null));
        assertThat(openStart).hasSize(1);
    }

    @Test
    void 사용자와_결과_필터() {
        List<PortalLogEntry> admin = repository.findAll(PortalLogSpecifications.filter(null, null, null, null, "superadmin", null));
        assertThat(admin).hasSize(3);

        List<PortalLogEntry> failures = repository.findAll(PortalLogSpecifications.filter(null, null, null, null, null, PortalLogResult.FAILURE));
        assertThat(failures).extracting(PortalLogEntry::getType)
                .containsExactlyInAnyOrder(PortalLogType.AUDIT, PortalLogType.SYSTEM);
    }

    @Test
    void 진행중_ACTION_행을_ref로_찾아_완료로_갱신한다() {
        PortalLogEntry inProgress = repository.findFirstByRefTypeAndRefIdAndResultOrderByStartedAtDesc(
                "INFRA_JOB", "job-1", PortalLogResult.IN_PROGRESS).orElseThrow();
        assertThat(inProgress.getCompletedAt()).isNull();

        inProgress.complete(PortalLogResult.SUCCESS, null, BASE.plusHours(3).plusMinutes(5));
        repository.saveAndFlush(inProgress);

        assertThat(repository.findFirstByRefTypeAndRefIdAndResultOrderByStartedAtDesc(
                "INFRA_JOB", "job-1", PortalLogResult.IN_PROGRESS)).isEmpty();
        assertThat(repository.findById(inProgress.getId()).orElseThrow().getResult()).isEqualTo(PortalLogResult.SUCCESS);
    }

    @Test
    void 길이를_넘는_문자열은_잘라_저장하고_빈_내용은_대시로_채운다() {
        String longContent = "x".repeat(600);
        PortalLogEntry saved = repository.saveAndFlush(PortalLogEntry.builder(PortalLogType.AUDIT)
                .content(longContent).description("d".repeat(1200)).screen("s".repeat(80)).build());
        PortalLogEntry blank = repository.saveAndFlush(PortalLogEntry.builder(PortalLogType.SYSTEM).content("  ").build());

        assertThat(saved.getContent()).hasSize(500);
        assertThat(saved.getDescription()).hasSize(1000);
        assertThat(saved.getScreen()).hasSize(50);
        assertThat(blank.getContent()).isEqualTo("-");
        assertThat(blank.getUserId()).isNull();
    }

    private List<PortalLogEntry> search(String keyword) {
        return repository.findAll(PortalLogSpecifications.filter(null, keyword, null, null, null, null));
    }
}
