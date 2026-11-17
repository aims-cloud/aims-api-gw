package com.innogrid.aims.scheduler.inventory;

import java.net.SocketTimeoutException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import com.innogrid.aims.common.exception.BusinessException;
import com.innogrid.aims.openstack.NovaClient;
import com.innogrid.aims.scheduler.config.SchedulerProperties;
import com.innogrid.aims.scheduler.model.InventoryStatus;

import jakarta.annotation.PreDestroy;

/**
 * Nova 인벤토리를 읽어 스케줄러 입력 모양으로 바꾼다 — 읽기 전용이고 저장하지 않는다.
 *
 * <p>다른 담당의 가상화 자산 조회는 OpenStack 예외를 삼켜 빈 목록을 내므로 연동 실패와 실제로 빈 인벤토리를 가를 수 없다.
 * 여기서는 {@link NovaClient} 를 직접 불러 예외를 그대로 받아 {@link InventoryStatus} 로 나눈다.
 * {@link NovaClient} 와 Keystone 클라이언트는 다른 담당 코드라 고치지 않는다.</p>
 *
 * <p>OpenStack 클라이언트에는 응답 시간 상한이 없어, 조회 전체를 전용 스레드 하나에서 돌리고 {@code timeoutMs} 까지만 기다린다.
 * 상한을 넘기면 기다리기만 멈추며 HTTP 호출은 OpenStack 이 응답할 때까지 그 스레드를 붙잡는다. 앞선 조회가 끝나기 전(정상 진행 중 포함)에 들어온 조회는 다시 부르지 않고
 * {@link InventoryStatus#TIMEOUT} 으로 바로 돌려준다 — 메트릭 질의 상한({@code MetricsQueryGuard})과 같은 한계이고, 풀을 따로 두어
 * 노드 메트릭 수집에는 영향이 없다.</p>
 */
@Component
public class NovaInventoryGateway {

    private static final Logger log = LoggerFactory.getLogger(NovaInventoryGateway.class);

    /** 한 번의 조회에서 따로 요청하는 flavor 수 상한. 넘는 VM 은 요구 자원을 모른다고 적는다. */
    static final int FLAVOR_LOOKUP_LIMIT = 50;

    /** 지워졌거나 오류 상태라 워크로드 입력으로 쓰지 않는 VM 상태. */
    private static final Set<String> NOT_CONVERTIBLE_STATUS = Set.of("ERROR", "DELETED", "SOFT_DELETED");

    private static final Comparator<String> NULLS_LAST = Comparator.nullsLast(Comparator.naturalOrder());

    private final NovaClient nova;
    private final long timeoutMs;
    private final ThreadPoolExecutor worker;
    /** 지금 상류를 부르고 있는 조회 수(0 또는 1). 시간 상한으로 기다리기를 멈춘 조회도 응답이 올 때까지 센다. */
    private final AtomicInteger running = new AtomicInteger();

    @Autowired
    public NovaInventoryGateway(NovaClient nova, SchedulerProperties properties) {
        this(nova, properties.getInventory().getTimeoutMs());
    }

    NovaInventoryGateway(NovaClient nova, long timeoutMs) {
        this.nova = nova;
        this.timeoutMs = timeoutMs;
        this.worker = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(1), task -> {
            Thread thread = new Thread(task, "scheduler-nova-inventory");
            thread.setDaemon(true);
            return thread;
        });
    }

    /** 인벤토리를 한 번 읽는다. 실패해도 예외를 던지지 않고 상태로 돌려준다. */
    public NovaInventorySnapshot read() {
        OffsetDateTime checkedAt = OffsetDateTime.now(ZoneOffset.UTC);
        long start = System.nanoTime();
        if (running.get() > 0) {
            log.warn("Nova 인벤토리 조회를 건너뜀 — 앞선 조회가 아직 상류 응답을 기다리는 중");
            return NovaInventorySnapshot.failed(InventoryStatus.TIMEOUT, checkedAt, elapsedMs(start));
        }
        Future<NovaInventorySnapshot> future;
        try {
            future = worker.submit(() -> {
                running.incrementAndGet();
                try {
                    return collect(checkedAt, start);
                } finally {
                    running.decrementAndGet();
                }
            });
        } catch (RejectedExecutionException e) {
            log.warn("Nova 인벤토리 조회를 건너뜀 — 대기 중인 조회가 이미 있음");
            return NovaInventorySnapshot.failed(InventoryStatus.TIMEOUT, checkedAt, elapsedMs(start));
        }
        try {
            return future.get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            log.warn("Nova 인벤토리 조회가 시간 상한 {}ms 안에 끝나지 않음", timeoutMs);
            return NovaInventorySnapshot.failed(InventoryStatus.TIMEOUT, checkedAt, elapsedMs(start));
        } catch (ExecutionException e) {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            InventoryStatus status = classify(cause);
            log.warn("Nova 인벤토리 조회 실패 {} — {}: {}", status, cause.getClass().getSimpleName(), cause.getMessage());
            return NovaInventorySnapshot.failed(status, checkedAt, elapsedMs(start));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            future.cancel(true);
            return NovaInventorySnapshot.failed(InventoryStatus.TIMEOUT, checkedAt, elapsedMs(start));
        }
    }

    @PreDestroy
    void shutdown() {
        worker.shutdownNow();
    }

    /**
     * 실패 예외를 상태로 나눈다. 순서가 중요하다 — 응답 오류와 연결 오류가 둘 다 {@link RestClientException} 의 하위형이다.
     */
    static InventoryStatus classify(Throwable failure) {
        if (failure instanceof MalformedInventoryException) return InventoryStatus.MALFORMED;
        if (failure instanceof BusinessException business) {
            // 503 은 접속 정보 미설정(Nova · Keystone 모두), 502 는 토큰 헤더 없는 Keystone 응답
            return business.getStatus() == HttpStatus.SERVICE_UNAVAILABLE ? InventoryStatus.NOT_CONFIGURED : InventoryStatus.UPSTREAM_ERROR;
        }
        if (failure instanceof RestClientResponseException response) {
            int code = response.getStatusCode().value();
            if (code == 401) return InventoryStatus.AUTH_FAILED;
            if (code == 403) return InventoryStatus.FORBIDDEN;
            return InventoryStatus.UPSTREAM_ERROR;
        }
        if (failure instanceof ResourceAccessException access) {
            return access.getCause() instanceof SocketTimeoutException ? InventoryStatus.TIMEOUT : InventoryStatus.CONNECTION_FAILED;
        }
        if (failure instanceof RestClientException) return InventoryStatus.MALFORMED;
        return InventoryStatus.UPSTREAM_ERROR;
    }

    private NovaInventorySnapshot collect(OffsetDateTime checkedAt, long start) {
        List<Map<String, Object>> hypervisors = list(nova.listHypervisors(), "hypervisors");
        List<String> unavailable = new ArrayList<>();

        Map<String, String> zoneByHost = new HashMap<>();
        try {
            zoneByHost = zones(nova.listAvailabilityZonesDetail());
        } catch (RuntimeException e) {
            unavailable.add("availabilityZones");
            log.warn("Nova 가용영역 조회 실패 — 노드의 가용영역을 비워 둠: {}", e.getMessage());
        }
        List<NovaInventorySnapshot.Node> nodes = new ArrayList<>();
        for (Map<String, Object> hypervisor : hypervisors) {
            nodes.add(toNode(hypervisor, zoneByHost));
        }
        nodes.sort(Comparator.comparing(NovaInventorySnapshot.Node::hypervisorHostname, NULLS_LAST));

        boolean serversRead = true;
        List<NovaInventorySnapshot.Server> servers = List.of();
        try {
            servers = toServers(list(nova.listServersAllTenants(), "servers"), unavailable);
        } catch (RuntimeException e) {
            serversRead = false;
            unavailable.add("servers");
            log.warn("Nova VM 목록 조회 실패 — VM 건수를 모름: {}", e.getMessage());
        }
        InventoryStatus status = nodes.isEmpty() ? InventoryStatus.EMPTY : InventoryStatus.OK;
        return new NovaInventorySnapshot(status, checkedAt, elapsedMs(start), List.copyOf(unavailable), List.copyOf(nodes), serversRead, servers);
    }

    static NovaInventorySnapshot.Node toNode(Map<String, Object> hypervisor, Map<String, String> zoneByHost) {
        String hostname = text(hypervisor.get("hypervisor_hostname"));
        String serviceHost = hypervisor.get("service") instanceof Map<?, ?> service ? text(service.get("host")) : null;
        String zone = zoneByHost.get(serviceHost != null ? serviceHost : hostname);
        String state = text(hypervisor.get("state"));
        String status = text(hypervisor.get("status"));
        Integer totalCpu = intOrNull(hypervisor.get("vcpus"));
        Long totalMemoryMb = longOrNull(hypervisor.get("memory_mb"));

        List<String> missing = new ArrayList<>();
        if (hostname == null) missing.add("hypervisor_hostname");
        if (totalCpu == null) missing.add("vcpus");
        if (totalMemoryMb == null) missing.add("memory_mb");
        String reason = null;
        if (!missing.isEmpty()) reason = "필드 누락 · 숫자 아님: " + String.join(", ", missing);
        else if (!"up".equalsIgnoreCase(state)) reason = "state " + state;
        else if (!"enabled".equalsIgnoreCase(status)) reason = "status " + status;

        return new NovaInventorySnapshot.Node(hostname, zone, state, status,
                totalCpu, intOrNull(hypervisor.get("vcpus_used")),
                totalMemoryMb, longOrNull(hypervisor.get("memory_mb_used")),
                longOrNull(hypervisor.get("local_gb")), longOrNull(hypervisor.get("local_gb_used")),
                intOrNull(hypervisor.get("running_vms")), reason == null, reason);
    }

    private List<NovaInventorySnapshot.Server> toServers(List<Map<String, Object>> raw, List<String> unavailable) {
        Map<String, Flavor> flavors = new HashMap<>();
        int lookups = 0;
        boolean flavorTrouble = false;
        List<NovaInventorySnapshot.Server> servers = new ArrayList<>();
        for (Map<String, Object> server : raw) {
            String status = text(server.get("status"));
            String flavorId = null;
            Flavor flavor = null;
            String reason = null;
            if (server.get("flavor") instanceof Map<?, ?> embedded) {
                // 마이크로버전 2.47 이상은 id 대신 original_name 과 자원 값을 직접 싣는다
                flavorId = text(embedded.get("id") != null ? embedded.get("id") : embedded.get("original_name"));
                flavor = Flavor.of(embedded);
                if (flavor == null && flavorId == null) reason = "flavor 없음";
                else if (flavor == null && flavors.containsKey(flavorId)) flavor = flavors.get(flavorId);
                else if (flavor == null && lookups >= FLAVOR_LOOKUP_LIMIT) reason = "flavor 조회 상한 초과";
                else if (flavor == null) {
                    lookups++;
                    flavor = lookupFlavor(flavorId);
                    flavors.put(flavorId, flavor);
                }
                if (reason == null && flavor == null) reason = "flavor 조회 실패: " + flavorId;
            } else {
                reason = "flavor 없음";
            }
            if (reason != null && !"flavor 없음".equals(reason)) flavorTrouble = true;
            if (reason == null && status != null && NOT_CONVERTIBLE_STATUS.contains(status.toUpperCase())) reason = "상태 " + status;
            servers.add(new NovaInventorySnapshot.Server(text(server.get("id")), text(server.get("name")), status,
                    text(server.get("OS-EXT-SRV-ATTR:hypervisor_hostname")), flavorId,
                    flavor == null ? null : flavor.vcpus(), flavor == null ? null : flavor.ramMb(), flavor == null ? null : flavor.diskGb(),
                    reason == null, reason));
        }
        if (flavorTrouble) unavailable.add("flavors");
        servers.sort(Comparator.comparing(NovaInventorySnapshot.Server::hypervisorHostname, NULLS_LAST)
                .thenComparing(NovaInventorySnapshot.Server::name, NULLS_LAST));
        return List.copyOf(servers);
    }

    /** flavor 하나를 읽는다. 실패하면 null — 그 flavor 를 쓰는 VM 만 요구 자원을 모른다고 적는다. */
    private Flavor lookupFlavor(String flavorId) {
        try {
            Map<String, Object> response = nova.getFlavor(flavorId);
            return response == null ? null : Flavor.of(response.get("flavor"));
        } catch (RuntimeException e) {
            log.warn("Nova flavor {} 조회 실패: {}", flavorId, e.getMessage());
            return null;
        }
    }

    /** 가용영역 상세 응답에서 호스트 → 가용영역 이름. */
    static Map<String, String> zones(Map<String, Object> response) {
        Map<String, String> zoneByHost = new HashMap<>();
        for (Map<String, Object> zone : list(response, "availabilityZoneInfo")) {
            String zoneName = text(zone.get("zoneName"));
            if (zoneName != null && zone.get("hosts") instanceof Map<?, ?> hosts) {
                for (Object host : hosts.keySet()) zoneByHost.put(String.valueOf(host), zoneName);
            }
        }
        return zoneByHost;
    }

    @SuppressWarnings("unchecked")
    static List<Map<String, Object>> list(Map<String, Object> response, String key) {
        if (response == null || !(response.get(key) instanceof List<?> items)) {
            throw new MalformedInventoryException("응답에 목록 키 " + key + " 가 없습니다");
        }
        List<Map<String, Object>> maps = new ArrayList<>();
        for (Object item : items) {
            if (!(item instanceof Map<?, ?>)) throw new MalformedInventoryException(key + " 목록에 객체가 아닌 항목이 있습니다");
            maps.add((Map<String, Object>) item);
        }
        return maps;
    }

    private static String text(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static Integer intOrNull(Object value) {
        Long parsed = longOrNull(value);
        return parsed == null || parsed > Integer.MAX_VALUE || parsed < Integer.MIN_VALUE ? null : parsed.intValue();
    }

    private static Long longOrNull(Object value) {
        if (value instanceof Number number) return number.longValue();
        if (value instanceof String s) {
            try {
                return Long.parseLong(s.trim());
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }

    private static long elapsedMs(long start) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
    }

    /** VM 이 요구하는 자원 — 서버 상세에 들어 있거나(마이크로버전 2.47 이상) flavor 를 따로 읽어 얻는다. */
    record Flavor(int vcpus, int ramMb, int diskGb) {
        static Flavor of(Object value) {
            if (!(value instanceof Map<?, ?> map)) return null;
            Integer vcpus = intOrNull(map.get("vcpus"));
            Integer ram = intOrNull(map.get("ram"));
            if (vcpus == null || ram == null) return null;
            Integer disk = intOrNull(map.get("disk"));
            return new Flavor(vcpus, ram, disk == null ? 0 : disk);
        }
    }

    /** 응답에 목록 키가 없거나 목록 항목이 객체가 아님. */
    static final class MalformedInventoryException extends RuntimeException {
        MalformedInventoryException(String message) {
            super(message);
        }
    }
}
