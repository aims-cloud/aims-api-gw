package com.innogrid.aims.monitoring.dto.alarm.alarmPolicy;

import java.util.List;

/**
 * 전송 설정의 수신자 선택 — 조직 트리(하위 조직 + 멤버)를 반환한다.
 * 조직·사용자 원장의 원천(auth 도메인 확장 vs 별도 조직 원장)은 TODO:: 확정 필요.
 */
public record AlarmRecipientCandidatesResponse(
        List<OrgNode> organizations
) {
    /** 조직 트리 항목 — children 은 하위 조직, members 는 소속 개인 */
    public record OrgNode(String orgId, String name, List<OrgNode> children, List<Member> members) {}

    /** 조직 멤버 1명 */
    public record Member(String userId, String name) {}

    public AlarmRecipientCandidatesResponse {
        organizations = organizations == null ? List.of() : List.copyOf(organizations);
    }
}
