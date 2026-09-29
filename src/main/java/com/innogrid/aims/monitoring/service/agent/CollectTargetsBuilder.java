package com.innogrid.aims.monitoring.service.agent;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Pattern;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.innogrid.aims.monitoring.entity.CollectService;
import org.springframework.stereotype.Component;

/**
 * 수집 서비스 원장 → Alloy {@code discovery.file} 이 읽는 file_sd JSON (docs/11 §5.3).
 *
 * <pre>
 * [ { "targets": ["localhost"],
 *     "labels": { "__path__": "/var/log/nginx/access.log", "job": "collect/nginx", "service_id": "12" } } ]
 * </pre>
 *
 * 경로순으로 정렬해 같은 원장이면 같은 문자열·해시가 나온다. {@code log_enabled} 인 서비스의 경로만 넣고,
 * 정적 설정이 이미 읽는 경로({@link AgentProperties#getStaticLogPaths()})는 제외한다. 경로는 호스트 경로 그대로이며
 * 컨테이너 안 접두어(/host)는 config.alloy 가 붙였다 뗀다.
 */
@Component
public class CollectTargetsBuilder {

    /** 목록 문자열과 SHA-256 해시, 대상 파일 수 */
    public record Targets(String json, String hash, int count) {}

    /** Spring Boot 4 의 자동 구성 ObjectMapper 는 Jackson 3 타입이라 빈 주입 대신 직접 만든다(PrometheusTargetService 와 같은 방식). */
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final List<Pattern> staticPaths;

    public CollectTargetsBuilder(AgentProperties properties) {
        List<Pattern> patterns = new ArrayList<>();
        for (String glob : properties.getStaticLogPaths()) {
            patterns.add(globToPattern(glob));
        }
        this.staticPaths = List.copyOf(patterns);
    }

    public Targets build(List<CollectService> services) {
        TreeMap<String, Map<String, Object>> byPath = new TreeMap<>();
        for (CollectService service : services) {
            if (!service.isLogEnabled()) {
                continue;
            }
            for (String path : service.getLogPaths()) {
                if (isStatic(path) || byPath.containsKey(path)) {
                    continue;
                }
                Map<String, String> labels = new LinkedHashMap<>();
                labels.put("__path__", path);
                labels.put("job", "collect/" + service.getServiceName());
                labels.put("service_id", String.valueOf(service.getId()));
                Map<String, Object> target = new LinkedHashMap<>();
                target.put("targets", List.of("localhost"));
                target.put("labels", labels);
                byPath.put(path, target);
            }
        }
        String json = toJson(new ArrayList<>(byPath.values()));
        return new Targets(json, sha256(json), byPath.size());
    }

    /** 정적 설정이 이미 읽는 경로인가 */
    public boolean isStatic(String path) {
        for (Pattern pattern : staticPaths) {
            if (pattern.matcher(path).matches()) {
                return true;
            }
        }
        return false;
    }

    public static String sha256(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private String toJson(List<Map<String, Object>> targets) {
        try {
            return MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(targets) + "\n";
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("수집 대상 목록 직렬화 실패", e);
        }
    }

    /** {@code *} 만 지원하는 glob → 정규식. {@code *} 는 디렉터리 구분자를 넘지 않는다. */
    static Pattern globToPattern(String glob) {
        StringBuilder regex = new StringBuilder("^");
        for (char c : glob.toCharArray()) {
            if (c == '*') {
                regex.append("[^/]*");
            } else {
                regex.append(Pattern.quote(String.valueOf(c)));
            }
        }
        return Pattern.compile(regex.append('$').toString());
    }
}
