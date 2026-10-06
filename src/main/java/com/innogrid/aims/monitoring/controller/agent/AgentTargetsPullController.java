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
 * 수집 에이전트(Alloy)가 수집 대상 목록을 가져가는 API (PULL 전송, docs/11 §5.3).
 *
 * 호출 주체는 각 노드·VM 안에서 도는 Alloy 뿐이다 — 포털(프론트)도 Prometheus 도 부르지 않는다.
 * 백엔드가 접근하지 않는 대상(세부1 물리 노드, Tumblebug VM)에서는 목록 파일을 밀어 넣을 수 없으므로, 그 Alloy 가
 * {@code discovery.http} 로 이 API 를 30초마다 부른다. 응답은 Alloy {@code discovery.http} 가 읽는 목록 JSON
 * ({@code [{"targets":[...],"labels":{...}}]}, LOCAL 전송이 파일로 쓰는 log-targets.json 과 같은 내용)이다.
 *
 * 인증은 없다. 물리 노드는 {@code instance}(node_exporter {@code address:port}, Alloy 의 NODE_INSTANCE), VM 은
 * {@code tbNs·tbInfra·tbNode}(Alloy 의 TB_NS·TB_INFRA·TB_NODE)로 식별하며, 둘 중 한 형태만 준다. 그 값은 Alloy 가 어차피
 * 라벨용으로 갖고 있어 추가로 줄 비밀값이 없다. 백엔드 8080 이 사내 대역(및 우리 VM 출발지)에서만 열려 있다는 전제이고
 * 응답에는 등록된 로그 파일 경로만 있다. 사용자 응답 봉투(ApiResponse)로 감싸지 않는다 — Alloy 가 배열을 그대로 읽는다.
 */
@Tag(name = "수집 에이전트 (Alloy 폴링)",
        description = "노드·VM 의 Alloy 가 자기 식별 라벨로 수집 대상 목록(file_sd JSON)을 가져간다. 사용자 API 가 아니다.")
@RestController
@RequestMapping("/api/v1/agent")
public class AgentTargetsPullController {

    private final AgentTargetsService targets;

    public AgentTargetsPullController(AgentTargetsService targets) {
        this.targets = targets;
    }

    @Operation(summary = "수집 대상 목록 (Alloy 용)",
            description = "각 노드·VM 의 Alloy(`discovery.http`)가 30초마다 호출하는 API 입니다. 포털·Prometheus 가 부르는 API 가 아니며 인증이 없습니다. "
                    + "물리 노드는 `instance`(node_exporter address:port, Alloy NODE_INSTANCE), Tumblebug VM 은 `tbNs`·`tbInfra`·`tbNode` 로 "
                    + "식별하며 둘 중 한 형태만 줍니다(섞거나 비우면 400). 그 대상의 수집 대상 목록을 Alloy 가 읽는 목록 JSON"
                    + "(`[{\"targets\":[...],\"labels\":{...}}]`, log-targets.json 과 같은 내용)으로 돌려주고, 응답한 목록의 해시를 적용됨으로 "
                    + "기록합니다(`services/agent` 의 APPLIED). 등록된 PULL 에이전트가 아니면 404 입니다.")
    @GetMapping(value = "/targets", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> targets(
            @Parameter(description = "물리 노드: node_exporter 주소 address:port (Alloy NODE_INSTANCE)", example = "10.0.0.21:9100")
            @RequestParam(required = false) String instance,
            @Parameter(description = "VM: Tumblebug Namespace id") @RequestParam(required = false) String tbNs,
            @Parameter(description = "VM: Tumblebug Infra id") @RequestParam(required = false) String tbInfra,
            @Parameter(description = "VM: Tumblebug Node(VM) id") @RequestParam(required = false) String tbNode) {
        boolean byInstance = instance != null;
        boolean byVm = tbNs != null || tbInfra != null || tbNode != null;
        if (byInstance == byVm) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "instance 또는 tbNs·tbInfra·tbNode 중 한 형태로 대상을 지정해야 합니다.");
        }
        Optional<String> json = byInstance ? targets.serve(instance) : targets.serveVm(tbNs, tbInfra, tbNode);
        if (json.isEmpty()) {
            String key = byInstance ? "instance=" + instance : "tb=" + tbNs + "/" + tbInfra + "/" + tbNode;
            throw new BusinessException(HttpStatus.NOT_FOUND, "해당하는 PULL 수집 에이전트가 없습니다: " + key);
        }
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(json.get());
    }
}
