package com.innogrid.aims.monitoring.controller.agent;

import java.util.Optional;

import com.innogrid.aims.common.exception.BusinessException;
import com.innogrid.aims.monitoring.service.agent.AgentTargetsService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 노드 Alloy 가 수집 대상 목록을 가져가는 API (PULL 전송, docs/11 §5.3).
 *
 * 호출 주체는 각 노드 안에서 도는 Alloy 뿐이다 — 포털(프론트)도 Prometheus 도 부르지 않는다.
 * 백엔드가 접근하지 않는 노드(세부1 물리 노드)에서는 목록 파일을 밀어 넣을 수 없으므로, 노드의 Alloy 가
 * {@code discovery.http} 로 이 API 를 30초마다 부른다. 응답은 Alloy {@code discovery.http} 가 읽는 목록 JSON
 * ({@code [{"targets":[...],"labels":{...}}]}, LOCAL 전송이 파일로 쓰는 log-targets.json 과 같은 내용)이다.
 *
 * 인증은 없다. 노드는 쿼리 {@code instance}(그 노드 node_exporter 의 {@code address:port}, Alloy 의 NODE_INSTANCE 와 같은 값)로
 * 식별하며, 그 값은 Alloy 가 어차피 라벨용으로 갖고 있어 노드에 추가로 줄 비밀값이 없다. 백엔드 8080 이 사내 대역에서만
 * 열려 있다는 전제이고 응답에는 등록된 로그 파일 경로만 있다. 사용자 응답 봉투(ApiResponse)로 감싸지 않는다 — Alloy 가 배열을 그대로 읽는다.
 */
@Tag(name = "수집 에이전트 (노드 Alloy 폴링)",
        description = "노드 Alloy 가 자기 instance 로 수집 대상 목록(file_sd JSON)을 가져간다. 사용자 API 가 아니다.")
@RestController
@RequestMapping("/api/v1/agent")
public class AgentTargetsPullController {

    private final AgentTargetsService targets;

    public AgentTargetsPullController(AgentTargetsService targets) {
        this.targets = targets;
    }

    @Operation(summary = "수집 대상 목록 (노드 Alloy 용)",
            description = "각 노드의 Alloy(`discovery.http`)가 30초마다 호출하는 API 입니다. 포털·Prometheus 가 부르는 API 가 아니며 인증이 없습니다. "
                    + "`instance`(그 노드 node_exporter 의 address:port, Alloy 의 NODE_INSTANCE)로 노드를 식별해 그 노드의 수집 대상 목록을 "
                    + "Alloy 가 읽는 목록 JSON(`[{\"targets\":[...],\"labels\":{...}}]`, log-targets.json 과 같은 내용)으로 돌려주고, "
                    + "응답한 목록의 해시를 적용됨으로 기록합니다(`GET /services/agent` 의 APPLIED). "
                    + "instance 가 등록된 node_exporter 타겟이 아니거나 그 자산이 PULL 에이전트가 아니면 404 입니다.")
    @GetMapping(value = "/targets", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> targets(
            @Parameter(description = "노드 node_exporter 주소 address:port (Alloy NODE_INSTANCE)", example = "10.0.0.21:9100")
            @RequestParam String instance) {
        Optional<String> json = targets.serve(instance);
        if (json.isEmpty()) {
            throw new BusinessException(HttpStatus.NOT_FOUND, "instance 에 해당하는 PULL 수집 에이전트 노드가 없습니다: " + instance);
        }
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(json.get());
    }
}
