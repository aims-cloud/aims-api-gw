package com.innogrid.aims.scheduler.service;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;

import com.innogrid.aims.common.exception.BusinessException;
import com.innogrid.aims.scheduler.dto.MdcLinkMatrixResponse;
import com.innogrid.aims.scheduler.dto.MdcLinkRequest;
import com.innogrid.aims.scheduler.entity.MdcLink;
import com.innogrid.aims.scheduler.repository.MdcLinkRepository;

import tools.jackson.databind.ObjectMapper;

import static org.junit.jupiter.api.Assertions.*;

/**
 * MDC 링크 원장의 검증·409·부분 수정·매트릭스 규칙. 실제 리포지토리(H2)와 액터(인증 없음 → SYSTEM)를 쓴다.
 */
@SpringBootTest
@Transactional
class MdcLinkServiceTest {

    @Autowired MdcLinkService service;
    @Autowired MdcLinkRepository repository;
    @Autowired ObjectMapper objectMapper;

    @BeforeEach
    void clean() {
        repository.deleteAll();
    }

    // ── 생성 검증 ──────────────────────────────────────────────

    @Test
    void 정상_생성은_쌍을_정렬하고_기본_상태_UP_출처_MANUAL_감사_SYSTEM() {
        MdcLink l = service.create(req("{\"mdcA\":\"mdc-02\",\"mdcB\":\"mdc-01\",\"latencyMs\":20}"));

        assertEquals("mdc-01", l.getMdcA());
        assertEquals("mdc-02", l.getMdcB());
        assertEquals(MdcLink.LinkStatus.UP, l.getStatus());
        assertEquals(MdcLink.LinkSource.MANUAL, l.getSource());
        assertEquals(ScheduleActorResolver.SYSTEM, l.getCreatedBy());
        assertNotNull(l.getCreatedAt());
    }

    @Test
    void 같은_MDC_사이는_400() {
        BusinessException e = assertThrows(BusinessException.class,
                () -> service.create(req("{\"mdcA\":\"a\",\"mdcB\":\"a\",\"latencyMs\":1}")));
        assertEquals(HttpStatus.BAD_REQUEST, e.getStatus());
        assertTrue(e.getMessage().contains("같은 MDC"));
    }

    @Test
    void MDC_식별자_누락_또는_지연_누락은_400() {
        assertBad(() -> service.create(req("{\"mdcB\":\"b\",\"latencyMs\":1}")), "mdcA");
        assertBad(() -> service.create(req("{\"mdcA\":\"a\",\"mdcB\":\" \",\"latencyMs\":1}")), "mdcB");
        assertBad(() -> service.create(req("{\"mdcA\":\"a\",\"mdcB\":\"b\"}")), "latencyMs");
    }

    @Test
    void 지연_음수_대역폭_0_손실률_범위_밖은_400() {
        assertBad(() -> service.create(req("{\"mdcA\":\"a\",\"mdcB\":\"b\",\"latencyMs\":-1}")), "0 이상");
        assertBad(() -> service.create(req("{\"mdcA\":\"a\",\"mdcB\":\"b\",\"latencyMs\":1,\"bandwidthMbps\":0}")), "대역폭");
        assertBad(() -> service.create(req("{\"mdcA\":\"a\",\"mdcB\":\"b\",\"latencyMs\":1,\"packetLossPercent\":101}")), "손실률");
        assertBad(() -> service.create(req("{\"mdcA\":\"a\",\"mdcB\":\"b\",\"latencyMs\":1,\"packetLossPercent\":\"x\"}")), "숫자");
    }

    @Test
    void 상태_출처는_대소문자를_무시하고_모르는_값은_400() {
        MdcLink l = service.create(req("{\"mdcA\":\"a\",\"mdcB\":\"b\",\"latencyMs\":1,\"status\":\"degraded\",\"source\":\"probe\"}"));
        assertEquals(MdcLink.LinkStatus.DEGRADED, l.getStatus());
        assertEquals(MdcLink.LinkSource.PROBE, l.getSource());

        assertBad(() -> service.create(req("{\"mdcA\":\"a\",\"mdcB\":\"c\",\"latencyMs\":1,\"status\":\"broken\"}")), "링크 상태");
    }

    @Test
    void 역방향_재등록은_409() {
        service.create(req("{\"mdcA\":\"a\",\"mdcB\":\"b\",\"latencyMs\":1}"));

        BusinessException e = assertThrows(BusinessException.class,
                () -> service.create(req("{\"mdcA\":\"b\",\"mdcB\":\"a\",\"latencyMs\":5}")));
        assertEquals(HttpStatus.CONFLICT, e.getStatus());
        assertTrue(e.getMessage().contains("역방향 포함"));
        assertEquals(1, repository.count());
    }

    @Test
    void 설명이_300자를_넘으면_400() {
        assertBad(() -> service.create(req("{\"mdcA\":\"a\",\"mdcB\":\"b\",\"latencyMs\":1,\"description\":\""
                + "x".repeat(301) + "\"}")), "300자");
    }

    // ── 부분 수정 ──────────────────────────────────────────────

    @Test
    void 부분_수정은_보낸_키만_반영하고_명시적_null_은_비운다() {
        MdcLink l = service.create(req("{\"mdcA\":\"a\",\"mdcB\":\"b\",\"latencyMs\":10,"
                + "\"bandwidthMbps\":1000,\"packetLossPercent\":0.5,\"description\":\"d\"}"));

        MdcLink kept = service.update(l.getId(), req("{\"status\":\"down\"}"));
        assertEquals(MdcLink.LinkStatus.DOWN, kept.getStatus());
        assertEquals(10.0, kept.getLatencyMs());
        assertEquals(1000.0, kept.getBandwidthMbps());
        assertEquals("d", kept.getDescription());

        MdcLink cleared = service.update(l.getId(), req("{\"bandwidthMbps\":null,\"packetLossPercent\":null,\"description\":null}"));
        assertNull(cleared.getBandwidthMbps());
        assertNull(cleared.getPacketLossPercent());
        assertNull(cleared.getDescription());
        assertEquals(MdcLink.LinkStatus.DOWN, cleared.getStatus(), "키 없음은 유지");
    }

    @Test
    void 쌍_변경은_둘_다_줘야_하고_다른_링크와_겹치면_409() {
        MdcLink ab = service.create(req("{\"mdcA\":\"a\",\"mdcB\":\"b\",\"latencyMs\":1}"));
        service.create(req("{\"mdcA\":\"a\",\"mdcB\":\"c\",\"latencyMs\":1}"));

        assertBad(() -> service.update(ab.getId(), req("{\"mdcA\":\"z\"}")), "함께");

        BusinessException e = assertThrows(BusinessException.class,
                () -> service.update(ab.getId(), req("{\"mdcA\":\"c\",\"mdcB\":\"a\"}")));
        assertEquals(HttpStatus.CONFLICT, e.getStatus());

        MdcLink moved = service.update(ab.getId(), req("{\"mdcA\":\"d\",\"mdcB\":\"a\"}"));
        assertEquals("a", moved.getMdcA());
        assertEquals("d", moved.getMdcB());

        MdcLink same = service.update(ab.getId(), req("{\"mdcA\":\"d\",\"mdcB\":\"a\",\"latencyMs\":7}"));
        assertEquals(7.0, same.getLatencyMs(), "자기 자신과 같은 쌍은 충돌이 아니다");
    }

    @Test
    void 없는_링크의_수정_삭제는_404() {
        BusinessException e = assertThrows(BusinessException.class, () -> service.update(999L, req("{}")));
        assertEquals(HttpStatus.NOT_FOUND, e.getStatus());
        assertEquals(HttpStatus.NOT_FOUND, assertThrows(BusinessException.class, () -> service.delete(999L)).getStatus());
    }

    // ── 목록·매트릭스 ──────────────────────────────────────────

    @Test
    void 목록은_MDC_필터와_상태_필터를_함께_적용한다() {
        service.create(req("{\"mdcA\":\"a\",\"mdcB\":\"b\",\"latencyMs\":1}"));
        service.create(req("{\"mdcA\":\"a\",\"mdcB\":\"c\",\"latencyMs\":1,\"status\":\"DOWN\"}"));
        service.create(req("{\"mdcA\":\"b\",\"mdcB\":\"c\",\"latencyMs\":1}"));

        assertEquals(3, service.list(null, null).size());
        assertEquals(2, service.list("a", null).size());
        assertEquals(List.of("c"), service.list("a", "down").stream().map(MdcLink::getMdcB).toList());
        assertEquals(2, service.list(null, "up").size());
        assertBad(() -> service.list(null, "broken"), "링크 상태");
    }

    @Test
    void 매트릭스는_저장된_링크로_대칭_행렬을_만들고_삭제를_반영한다() {
        MdcLink ab = service.create(req("{\"mdcA\":\"a\",\"mdcB\":\"b\",\"latencyMs\":10}"));
        service.create(req("{\"mdcA\":\"c\",\"mdcB\":\"a\",\"latencyMs\":80}"));

        MdcLinkMatrixResponse m = service.matrix();
        assertEquals(List.of("a", "b", "c"), m.mdcIds());
        assertEquals(10.0, m.links().get(0).get(1).latencyMs());
        assertEquals(80.0, m.links().get(2).get(0).latencyMs());
        assertNull(m.links().get(1).get(2));

        service.delete(ab.getId());
        MdcLinkMatrixResponse after = service.matrix();
        assertEquals(List.of("a", "c"), after.mdcIds(), "b 는 링크가 없어져 사라진다");
    }

    // ── 헬퍼 ───────────────────────────────────────────────────

    private MdcLinkRequest req(String json) {
        return objectMapper.readValue(json, MdcLinkRequest.class);
    }

    private static void assertBad(Runnable action, String messagePart) {
        BusinessException e = assertThrows(BusinessException.class, action::run);
        assertEquals(HttpStatus.BAD_REQUEST, e.getStatus(), e.getMessage());
        assertTrue(e.getMessage().contains(messagePart), "사유에 '" + messagePart + "' 가 없다: " + e.getMessage());
    }
}
