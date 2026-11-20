package com.innogrid.aims.monitoring.service.agent;

import java.io.IOException;
import com.innogrid.aims.monitoring.entity.AgentNode;
import com.innogrid.aims.monitoring.entity.AgentTransport;

/**
 * 수집 대상 목록 파일을 노드에 두는 전송기. 전송 방식({@link AgentTransport})마다 구현이 하나씩이며,
 * {@link AgentTargetsService} 가 노드의 transport 로 고른다. 지금은 LOCAL 만 있고 SSH 는 에이전트 매니저 구현 때 추가한다.
 */
public interface AgentTargetsPublisher {

    AgentTransport transport();

    /** 노드에 지금 놓여 있는 목록 파일의 SHA-256. 파일이 없으면 null. 읽기 실패는 예외 */
    String currentHash(AgentNode node) throws IOException;

    /** 목록 파일을 통째로 교체한다. 반쯤 쓰인 파일을 Alloy 가 읽지 않도록 원자적으로 바꾼다 */
    void publish(AgentNode node, String json) throws IOException;
}
