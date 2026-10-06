package com.innogrid.aims.monitoring.service.agent;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import com.innogrid.aims.monitoring.entity.CollectOwner;
import org.springframework.core.io.ClassPathResource;

/**
 * Tumblebug VM 수집 에이전트 설치 명령의 조립과 결과 판정 (docs/11 §5.5).
 *
 * 설치 스크립트({@code agent/vm/install.sh})·VM 별 환경 파일·Alloy 설정({@code agent/vm/config.alloy})을 각각 base64 로 실어
 * 명령 한 줄로 보낸다. 값이 셸 문자열에 직접 들어가지 않으므로 따옴표·치환 문제가 없고, 환경 파일 값은 형식 검사로 한 번 더 막는다.
 * 결과는 스크립트의 출력 계약(AIMS_STEP·AIMS_ERROR·AIMS_AGENT_OK)으로 판정한다.
 */
public final class VmAgentInstallCommand {

    /** Tumblebug id — 영숫자·-·_·. 만 쓴다. 목록 API 쿼리와 Loki 라벨에 그대로 들어간다 */
    static final Pattern TB_ID = Pattern.compile("[A-Za-z0-9._-]{1,200}");
    /** VM 이 부르는 주소 — 공백·따옴표·셸 메타문자 없는 http(s) URL */
    static final Pattern URL = Pattern.compile("https?://[^\\s'\"`$\\\\;&|<>()]+");
    static final Pattern VERSION = Pattern.compile("[0-9A-Za-z.+-]{1,32}");
    static final Pattern USER = Pattern.compile("[a-z_][a-z0-9_-]{0,31}");

    static final String OK_MARKER = "AIMS_AGENT_OK";
    static final String STEP_PREFIX = "AIMS_STEP=";
    static final String ERROR_PREFIX = "AIMS_ERROR=";
    /** 명령 자체를 보내지 못했거나 스크립트가 시작되기 전 실패 */
    public static final String STEP_SEND = "SEND_COMMAND";
    public static final String STEP_DONE = "DONE";
    private static final int MAX_ERROR = 2000;
    private static final int MAX_VERSION = 50;

    private final String script;
    private final String config;

    VmAgentInstallCommand(String script, String config) {
        this.script = stripCr(script);
        this.config = stripCr(config);
    }

    /** 패키지에 든 설치 스크립트와 Alloy 설정으로 만든다 */
    public static VmAgentInstallCommand fromClasspath() {
        return new VmAgentInstallCommand(read("agent/vm/install.sh"), read("agent/vm/config.alloy"));
    }

    /**
     * Tumblebug {@code POST /ns/{ns}/cmd/infra/{infra}?nodeId=} 본문(model.InfraCmdReq).
     *
     * @throws IllegalArgumentException VM 키·URL·버전·사용자 값이 형식에 맞지 않을 때
     */
    public Map<String, Object> requestBody(CollectOwner vm, AgentProperties.VmInstall settings) {
        String env = envFile(vm, settings);
        String line = "d=$(mktemp -d)"
                + " && echo " + base64(script) + " | base64 -d > $d/install.sh"
                + " && echo " + base64(env) + " | base64 -d > $d/alloy.env"
                + " && echo " + base64(config) + " | base64 -d > $d/config.alloy"
                + " && sudo bash $d/install.sh $d/alloy.env $d/config.alloy; rc=$?; rm -rf $d; exit $rc";
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("command", List.of(line));
        body.put("timeoutMinutes", Math.max(1, Math.min(120, settings.getTimeoutMinutes())));
        if (settings.getUserName() != null && !settings.getUserName().isBlank()) {
            body.put("userName", require(USER, settings.getUserName().trim(), "SSH 사용자"));
        }
        return body;
    }

    /** VM 의 /etc/aims-alloy/alloy.env — systemd EnvironmentFile 과 설치 스크립트가 함께 읽는다 */
    String envFile(CollectOwner vm, AgentProperties.VmInstall settings) {
        if (!vm.isVm()) {
            throw new IllegalArgumentException("VM 소유자가 아닙니다: " + vm.label());
        }
        return "TB_NS=" + require(TB_ID, vm.tbNs(), "Namespace id") + "\n"
                + "TB_INFRA=" + require(TB_ID, vm.tbInfra(), "Infra id") + "\n"
                + "TB_NODE=" + require(TB_ID, vm.tbNode(), "Node id") + "\n"
                + "LOKI_PUSH_URL=" + require(URL, trim(settings.getLokiPushUrl()), "Loki push 주소") + "\n"
                + "AIMS_TARGETS_URL=" + require(URL, trim(settings.getTargetsUrl()), "수집 대상 목록 API 주소") + "\n"
                + "ALLOY_VERSION=" + require(VERSION, trim(settings.getAlloyVersion()), "Alloy 버전") + "\n"
                + "NODE_EXPORTER_VERSION=" + require(VERSION, trim(settings.getNodeExporterVersion()), "node_exporter 버전") + "\n";
    }

    /** 설치 결과. 성공이면 step=DONE·version, 실패면 마지막으로 시작한 단계와 사유 */
    public record Outcome(boolean success, String step, String error, String version) {
        public static Outcome failed(String step, String error) {
            return new Outcome(false, step, truncate(error, MAX_ERROR), null);
        }
    }

    /**
     * Tumblebug 응답(model.InfraSshCmdResultForAPI: {@code results[].nodeId·stdout·stderr·error})에서 이 VM 의 결과를 판정한다.
     * stdout·stderr 는 명령 순번을 키로 한 맵이다. 성공 표식이 있으면 성공, 없으면 마지막 단계와 사유(AIMS_ERROR → Tumblebug error →
     * stderr 끝부분 순)를 남긴다. sudo 의 hostname 해석 경고(docs/07 §3.1)는 사유에서 뺀다.
     */
    @SuppressWarnings("unchecked")
    public static Outcome parse(Map<String, Object> response, String nodeId) {
        Object results = response == null ? null : response.get("results");
        if (!(results instanceof List<?> list) || list.isEmpty()) {
            return Outcome.failed(STEP_SEND, "Tumblebug 응답에 명령 결과가 없습니다.");
        }
        Map<String, Object> mine = null;
        for (Object item : list) {
            if (item instanceof Map<?, ?> map && nodeId.equals(map.get("nodeId"))) {
                mine = (Map<String, Object>) map;
                break;
            }
        }
        if (mine == null) {
            return Outcome.failed(STEP_SEND, "Tumblebug 응답에 이 VM(" + nodeId + ")의 결과가 없습니다.");
        }
        String stdout = joined(mine.get("stdout"));
        String stderr = joined(mine.get("stderr"));
        String tbError = mine.get("error") == null ? "" : String.valueOf(mine.get("error")).trim();

        String step = STEP_SEND;
        String scriptError = null;
        for (String line : stdout.split("\n")) {
            String trimmed = line.trim();
            if (trimmed.startsWith(OK_MARKER)) {
                return new Outcome(true, STEP_DONE, null, truncate(trimmed.substring(OK_MARKER.length()).trim(), MAX_VERSION));
            }
            if (trimmed.startsWith(STEP_PREFIX)) {
                step = trimmed.substring(STEP_PREFIX.length());
            } else if (trimmed.startsWith(ERROR_PREFIX)) {
                scriptError = trimmed.substring(ERROR_PREFIX.length());
            }
        }
        String reason = scriptError != null ? scriptError
                : !tbError.isEmpty() ? tbError
                : meaningfulTail(stderr);
        return Outcome.failed(step, reason == null || reason.isBlank() ? "설치 결과를 확인할 수 없습니다(성공 표식 없음)." : reason);
    }

    private static String joined(Object outputs) {
        if (outputs instanceof Map<?, ?> map) {
            List<String> keys = new ArrayList<>();
            for (Object key : map.keySet()) {
                keys.add(String.valueOf(key));
            }
            keys.sort((a, b) -> Integer.compare(order(a), order(b)));
            StringBuilder sb = new StringBuilder();
            for (String key : keys) {
                Object value = map.get(key);
                if (value != null) {
                    sb.append(value).append('\n');
                }
            }
            return sb.toString();
        }
        return outputs == null ? "" : String.valueOf(outputs);
    }

    private static int order(String key) {
        try {
            return Integer.parseInt(key);
        } catch (NumberFormatException e) {
            return Integer.MAX_VALUE;
        }
    }

    private static String meaningfulTail(String stderr) {
        List<String> lines = new ArrayList<>();
        for (String line : stderr.split("\n")) {
            String trimmed = line.trim();
            if (!trimmed.isEmpty() && !trimmed.startsWith("sudo: unable to resolve host")) {
                lines.add(trimmed);
            }
        }
        if (lines.isEmpty()) {
            return null;
        }
        return String.join("\n", lines.subList(Math.max(0, lines.size() - 10), lines.size()));
    }

    private static String require(Pattern pattern, String value, String name) {
        if (value == null || !pattern.matcher(value).matches()) {
            throw new IllegalArgumentException(name + " 형식이 설치 명령에 쓸 수 없는 값입니다: " + value);
        }
        return value;
    }

    private static String trim(String value) {
        return value == null ? null : value.trim();
    }

    private static String truncate(String value, int max) {
        return value == null || value.length() <= max ? value : value.substring(0, max);
    }

    private static String base64(String text) {
        return Base64.getEncoder().encodeToString(text.getBytes(StandardCharsets.UTF_8));
    }

    /** Windows 에서 빌드해 CRLF 가 섞여도 VM 의 bash·Alloy 가 읽을 수 있게 LF 로 맞춘다 */
    private static String stripCr(String text) {
        return text.replace("\r", "");
    }

    private static String read(String path) {
        try (InputStream in = new ClassPathResource(path).getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("VM 에이전트 설치 리소스를 읽을 수 없습니다: " + path, e);
        }
    }
}
