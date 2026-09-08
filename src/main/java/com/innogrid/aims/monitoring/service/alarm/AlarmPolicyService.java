package com.innogrid.aims.monitoring.service.alarm;

import java.util.List;

import com.innogrid.aims.common.dto.PageResponse;
import com.innogrid.aims.monitoring.dto.alarm.alarmPolicy.AlarmLevel;
import com.innogrid.aims.monitoring.dto.alarm.alarmPolicy.AlarmMetricCatalogResponse;
import com.innogrid.aims.monitoring.dto.alarm.alarmPolicy.AlarmPolicyDetailResponse;
import com.innogrid.aims.monitoring.dto.alarm.alarmPolicy.AlarmPolicyListItemResponse;
import com.innogrid.aims.monitoring.dto.alarm.alarmPolicy.AlarmPolicyRecipientResponse;
import com.innogrid.aims.monitoring.dto.alarm.alarmPolicy.AlarmPolicyRequest;
import com.innogrid.aims.monitoring.dto.alarm.alarmPolicy.AlarmPolicyResourceResponse;
import com.innogrid.aims.monitoring.dto.alarm.alarmPolicy.AlarmRecipientCandidatesResponse;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * 관측 > 알람 > 정책 탭 조회·관리 서비스.
 * 정책 목록·상세·CRUD·복제와 하단 탭(대상 자원/전송 대상), 생성 위저드 보조(메트릭 카탈로그·수신자 후보)를 제공한다.
 * 발생 이력 탭은 AlarmHistoryService(policyId 필터)를 재사용한다.
 * 기존 AlarmController 의 규칙 CRUD(/rules)는 이 정책 API 로 대체 예정이다.
 */
@Service
public class AlarmPolicyService {

    /** 정책 목록 — 역할·배포영역·자원 유형·특정 자원·레벨·키워드 필터, 컬럼 정렬, 페이징(1부터).
     *  resourceId 는 자원 탐색기의 알람 탭에서 해당 자원이 대상에 포함된 정책만 조회할 때 사용한다. */
    public PageResponse<AlarmPolicyListItemResponse> getPolicies(
            String roleName, String areaId, String resourceType, String resourceId,
            AlarmLevel level, String keyword, int page, int size, String sort) {
        // TODO:: 정책 원장(AlarmRule 확장 또는 신규 엔티티 — 유형/범위/전송 설정 포함) 확정 후 구현
        throw dataSourceNotImplemented();
    }

    /** 정책 상세 — 하단 상세 탭·상세 페이지 상단 표 공용 */
    public AlarmPolicyDetailResponse getPolicy(Long policyId) {
        throw dataSourceNotImplemented();
    }

    /** 정책 생성 — 위저드 4단계(대상/조건/전송/요약) payload. 생성자는 인증 사용자 기준 기록 */
    public AlarmPolicyDetailResponse createPolicy(AlarmPolicyRequest request) {
        // TODO:: AlarmEvaluationService 평가 대상에 신규 정책 반영 (메트릭 하드코딩 4종 제약 해소 포함)
        throw dataSourceNotImplemented();
    }

    /** 정책 편집 */
    public AlarmPolicyDetailResponse updatePolicy(Long policyId, AlarmPolicyRequest request) {
        throw dataSourceNotImplemented();
    }

    /** 정책 복제 — 원본 설정 복사본 생성 (이름 중복 처리 정책 TODO:: 확정 필요) */
    public AlarmPolicyDetailResponse duplicatePolicy(Long policyId) {
        throw dataSourceNotImplemented();
    }

    /** 정책 삭제 — 목록 체크박스 다중 선택 */
    public void deletePolicies(List<Long> policyIds) {
        throw dataSourceNotImplemented();
    }

    /** 대상 자원 탭 — 정책이 적용되는 자원 목록 (이름/별칭/ID), 키워드 필터·페이징 */
    public PageResponse<AlarmPolicyResourceResponse> getPolicyResources(
            Long policyId, String keyword, int page, int size, String sort) {
        // TODO:: targetScope=ALL 이면 적용 범위 내 전체 자원을 동적 조회
        throw dataSourceNotImplemented();
    }

    /** 전송 대상 탭 — 정책의 수신자 목록 (이름/유형) */
    public List<AlarmPolicyRecipientResponse> getPolicyRecipients(Long policyId) {
        throw dataSourceNotImplemented();
    }

    /** 위저드 조건 설정 — 유형별 메트릭 카탈로그 (미리보기 차트는 자원 탐색기 trend API 재사용) */
    public List<AlarmMetricCatalogResponse> getMetricCatalog(String resourceType) {
        // TODO:: MetricCatalogService 와 통합 검토
        throw dataSourceNotImplemented();
    }

    /** 위저드 전송 설정 — 수신자 후보 조직 트리(하위 조직 + 멤버) */
    public AlarmRecipientCandidatesResponse getRecipientCandidates(String keyword) {
        // TODO:: 조직·사용자 원장 원천(auth 도메인 확장 vs 별도 조직 원장) 확정 필요
        throw dataSourceNotImplemented();
    }

    private ResponseStatusException dataSourceNotImplemented() {
        return new ResponseStatusException(
                HttpStatus.NOT_IMPLEMENTED,
                "알람 정책 원장과 정책 탭 화면의 매핑 구현 전입니다.");
    }
}
