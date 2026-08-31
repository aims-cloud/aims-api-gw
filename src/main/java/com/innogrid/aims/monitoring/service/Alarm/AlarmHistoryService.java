package com.innogrid.aims.monitoring.service.Alarm;

import java.time.Instant;
import java.util.List;

import com.innogrid.aims.common.dto.PageResponse;
import com.innogrid.aims.monitoring.dto.alarm.alarmHistory.AlarmAcknowledgeRequest;
import com.innogrid.aims.monitoring.dto.alarm.alarmHistory.AlarmAcknowledgementResponse;
import com.innogrid.aims.monitoring.dto.alarm.alarmPolicy.AlarmChannel;
import com.innogrid.aims.monitoring.dto.alarm.alarmHistory.AlarmDeliveryResponse;
import com.innogrid.aims.monitoring.dto.alarm.alarmHistory.AlarmHistoryListItemResponse;
import com.innogrid.aims.monitoring.dto.alarm.alarmPolicy.AlarmLevel;
import com.innogrid.aims.monitoring.dto.alarm.alarmHistory.AlarmMessageDetailResponse;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * 관측 > 알람 > 발생 이력 탭 조회·확인 서비스.
 * 목록·전송 이력·메시지 상세·알람 확인(쓰기)·확인 내역을 제공한다.
 * 전송 이력은 알림 발송 체계(Email/SMS 발송기 + 전송 결과 적재) 구축이 전제다.
 * 알람 정책 탭은 기존 AlarmController(규칙 CRUD)를 화면 확정 후 개편한다.
 */
@Service
public class AlarmHistoryService {

    /** 발생 이력 목록 — 레벨·역할·배포영역·자원 유형·기간·키워드 필터, 컬럼 정렬, 페이징(1부터) */
    public PageResponse<AlarmHistoryListItemResponse> getHistories(
            List<AlarmLevel> levels, String roleId, String deploymentId, String resourceType,
            Long policyId, String keyword, Instant from, Instant to, int page, int size, String sort) {
        // TODO:: AlarmHistory 엔티티에 역할/배포영역/정책 링크/확인 정보 필드 확장 후 조회 구현
        throw dataSourceNotImplemented();
    }

    /** 전송 이력 (행 확장) — 대상별 채널 전송 결과 (개인: 단일 상태, 조직: 카운트) */
    public List<AlarmDeliveryResponse> getDeliveries(Long alarmId) {
        // TODO:: 알림 발송 체계(전송 대상 원장 + 발송 결과 적재) 구축 후 구현
        throw dataSourceNotImplemented();
    }

    /** 메시지 상세 모달 — 발송 메시지 본문과 전송 대상 목록(수신 주소·결과·실패 사유·전송 일시) */
    public AlarmMessageDetailResponse getMessageDetail(Long alarmId, Long deliveryId, AlarmChannel channel) {
        // TODO:: 발송 시점 치환 메시지 본문 저장 방식 확정 후 구현
        throw dataSourceNotImplemented();
    }

    /** 알람 확인 — 확인 내용 저장, 확인자·확인 시각은 인증 사용자 기준으로 서버가 기록 */
    public AlarmAcknowledgementResponse acknowledge(Long alarmId, AlarmAcknowledgeRequest request) {
        // TODO:: SecurityContext 의 인증 사용자를 확인자로 저장, 이미 확인된 알람 처리 정책 확정
        throw dataSourceNotImplemented();
    }

    /** 알람 확인 내역 모달 — 발생 정보 + 확인 일시/확인자/확인 내용 */
    public AlarmAcknowledgementResponse getAcknowledgement(Long alarmId) {
        // TODO:: 확인 정보 저장 필드 확장 후 구현
        throw dataSourceNotImplemented();
    }

    private ResponseStatusException dataSourceNotImplemented() {
        return new ResponseStatusException(
                HttpStatus.NOT_IMPLEMENTED,
                "알림 전송 이력·확인 데이터와 알람 발생 이력 화면의 매핑 구현 전입니다.");
    }
}
