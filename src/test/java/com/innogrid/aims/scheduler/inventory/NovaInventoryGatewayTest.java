package com.innogrid.aims.scheduler.inventory;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.innogrid.aims.openstack.KeystoneClient;
import com.innogrid.aims.openstack.NovaClient;
import com.innogrid.aims.openstack.OpenStackProperties;
import com.innogrid.aims.scheduler.inventory.NovaInventorySnapshot.Node;
import com.innogrid.aims.scheduler.inventory.NovaInventorySnapshot.Server;
import com.innogrid.aims.scheduler.model.InventoryStatus;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

/**
 * Nova 인벤토리 읽기 — 실패 종류 구분과 변환.
 *
 * <p>스프링 없이 실제 Keystone · Nova 클라이언트를 만들고, 테스트 안에서 띄운 HTTP 서버가 Keystone 토큰과 Nova 응답을 흉내 낸다.
 * 그래서 클라이언트가 실제로 던지는 예외 종류(401 · 403 · 5xx · 연결 거부 · 응답 지연)로 상태 분류를 확인한다.
 * 응답 본문은 Nova 2.1 문서 모양을 따른 {@code scheduler/inventory/*.json} 이다.</p>
 */
class NovaInventoryGatewayTest {

    private static final String TOKEN = "/v3/auth/tokens";
    private static final String HYPERVISORS = "/v2.1/os-hypervisors/detail";
    private static final String ZONES = "/v2.1/os-availability-zone/detail";
    private static final String SERVERS = "/v2.1/servers/detail";
    private static final String FLAVOR_SMALL = "/v2.1/flavors/f-small";
    private static final String FLAVOR_LARGE = "/v2.1/flavors/f-large";

    private Stub stub;
    private final List<NovaInventoryGateway> gateways = new ArrayList<>();

    @BeforeEach
    void start() throws IOException {
        stub = new Stub();
        stub.token();
        stub.reply(HYPERVISORS, 200, fixture("hypervisors.json"));
        stub.reply(ZONES, 200, fixture("availability-zones.json"));
        stub.reply(SERVERS, 200, fixture("servers.json"));
        stub.reply(FLAVOR_SMALL, 200, fixture("flavor-small.json"));
        stub.reply(FLAVOR_LARGE, 200, fixture("flavor-large.json"));
    }

    @AfterEach
    void stop() {
        gateways.forEach(NovaInventoryGateway::shutdown);
        stub.close();
    }

    @DisplayName("하이퍼바이저 2대와 VM 3대를 노드 · 워크로드 입력으로 바꾸고 호스트 이름순으로 싣는다")
    @Test
    void converts_hypervisors_and_servers_in_host_order() {
        NovaInventorySnapshot snapshot = gateway(5_000).read();

        assertEquals(InventoryStatus.OK, snapshot.status());
        assertEquals(List.of(), snapshot.unavailable());
        assertNotNull(snapshot.checkedAt());
        assertEquals(List.of(
                new Node("compute-a.local", "az-a", "up", "enabled", 16, 4, 65536L, 8192L, 450L, 40L, 2, true, null),
                new Node("compute-b.local", "az-b", "up", "enabled", 32, 40, 131072L, 65536L, 900L, 300L, 5, true, null)),
                snapshot.nodes(), "가용영역은 service.host 로 잇고, 오버커밋 할당(40 > 32)은 자르지 않는다");

        assertTrue(snapshot.serversRead());
        assertEquals(List.of(
                new Server("a1a1a1a1-0000-0000-0000-000000000001", "vm-a", "SHUTOFF", "compute-a.local", "f-small", 2, 4096, 40, true, null),
                new Server("b2b2b2b2-0000-0000-0000-000000000002", "vm-b", "ACTIVE", "compute-b.local", "f-small", 2, 4096, 40, true, null),
                new Server("c3c3c3c3-0000-0000-0000-000000000003", "vm-c", "SHELVED_OFFLOADED", null, "f-large", 8, 16384, 160, true, null)),
                snapshot.servers(), "호스트가 없는 VM 은 맨 뒤에 남는다");
    }

    @DisplayName("하이퍼바이저 목록이 비면 실패가 아니라 EMPTY 이고, 목록 키가 없으면 MALFORMED 다")
    @Test
    void empty_list_is_empty_and_missing_list_key_is_malformed() {
        stub.reply(HYPERVISORS, 200, "{\"hypervisors\":[]}");
        stub.reply(SERVERS, 200, "{\"servers\":[]}");
        NovaInventorySnapshot empty = gateway(5_000).read();
        assertEquals(InventoryStatus.EMPTY, empty.status());
        assertEquals(List.of(), empty.nodes());
        assertTrue(empty.serversRead(), "VM 목록도 읽었고 0건이다");

        stub.reply(HYPERVISORS, 200, "{\"hypervisor_list\":[]}");
        NovaInventorySnapshot malformed = gateway(5_000).read();
        assertEquals(InventoryStatus.MALFORMED, malformed.status());
        assertFalse(malformed.serversRead());
    }

    @DisplayName("접속 정보가 없으면 상류를 부르지 않고 NOT_CONFIGURED 다")
    @Test
    void missing_settings_are_not_configured() {
        NovaInventorySnapshot snapshot = gateway(new OpenStackProperties(), 5_000).read();

        assertEquals(InventoryStatus.NOT_CONFIGURED, snapshot.status());
        assertEquals(List.of(), snapshot.nodes());
        assertEquals(0, stub.hits(TOKEN) + stub.hits(HYPERVISORS));
    }

    @DisplayName("Keystone 이 401 이면 AUTH_FAILED 이고 Nova 를 부르지 않는다")
    @Test
    void keystone_401_is_auth_failed() {
        stub.reply(TOKEN, 401, "{\"error\":{\"code\":401,\"title\":\"Unauthorized\"}}");

        assertEquals(InventoryStatus.AUTH_FAILED, gateway(5_000).read().status());
        assertEquals(0, stub.hits(HYPERVISORS));
    }

    @DisplayName("하이퍼바이저 목록이 403 이면 401 과 나눠 FORBIDDEN 이다")
    @Test
    void nova_403_is_forbidden() {
        stub.reply(HYPERVISORS, 403, "{\"forbidden\":{\"code\":403}}");

        assertEquals(InventoryStatus.FORBIDDEN, gateway(5_000).read().status());
    }

    @DisplayName("닫힌 포트면 CONNECTION_FAILED 다")
    @Test
    void closed_port_is_connection_failed() throws IOException {
        int port;
        try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            port = socket.getLocalPort();
        }
        // Windows 는 연결 거부를 몇 번 다시 시도해 2초 안팎이 걸리므로 상한을 넉넉히 둔다
        NovaInventorySnapshot snapshot = gateway("http://127.0.0.1:" + port, 15_000).read();

        assertEquals(InventoryStatus.CONNECTION_FAILED, snapshot.status());
    }

    @DisplayName("Nova 5xx 와 토큰 헤더가 없는 Keystone 응답은 UPSTREAM_ERROR 다")
    @Test
    void server_error_and_missing_token_header_are_upstream_error() {
        stub.reply(HYPERVISORS, 500, "{\"computeFault\":{\"code\":500}}");
        assertEquals(InventoryStatus.UPSTREAM_ERROR, gateway(5_000).read().status());

        stub.reply(HYPERVISORS, 200, fixture("hypervisors.json"));
        stub.replies.put(TOKEN, new Reply(201, "{\"token\":{}}", Map.of(), 0));
        assertEquals(InventoryStatus.UPSTREAM_ERROR, gateway(5_000).read().status());
    }

    @DisplayName("상류가 상한보다 늦으면 상한 + 1초 안에 TIMEOUT 이고, 상류가 응답한 뒤의 조회는 정상이다")
    @Test
    void slow_upstream_times_out_and_later_call_recovers() throws InterruptedException {
        stub.reply(HYPERVISORS, 200, fixture("hypervisors.json"), 1_500);
        NovaInventoryGateway gateway = gateway(300);

        long start = System.nanoTime();
        NovaInventorySnapshot slow = gateway.read();
        long wallMs = (System.nanoTime() - start) / 1_000_000;
        assertEquals(InventoryStatus.TIMEOUT, slow.status());
        assertTrue(wallMs < 1_300, "상한 300ms + 1초 안에 돌아온다: " + wallMs + "ms");

        stub.reply(HYPERVISORS, 200, fixture("hypervisors.json"));
        InventoryStatus later = InventoryStatus.TIMEOUT;
        for (int i = 0; i < 50 && later == InventoryStatus.TIMEOUT; i++) {
            Thread.sleep(100);
            later = gateway.read().status();
        }
        assertEquals(InventoryStatus.OK, later, "붙잡혔던 호출이 끝나면 다시 읽는다");
    }

    @DisplayName("앞선 조회가 아직 상류를 기다리는 동안의 조회는 상류를 다시 부르지 않고 바로 TIMEOUT 이다")
    @Test
    void call_while_upstream_is_held_returns_timeout_at_once() {
        stub.reply(HYPERVISORS, 200, fixture("hypervisors.json"), 2_000);
        NovaInventoryGateway gateway = gateway(300);
        assertEquals(InventoryStatus.TIMEOUT, gateway.read().status());

        long start = System.nanoTime();
        NovaInventorySnapshot second = gateway.read();
        long wallMs = (System.nanoTime() - start) / 1_000_000;
        assertEquals(InventoryStatus.TIMEOUT, second.status());
        assertTrue(wallMs < 150, "기다리지 않고 바로 돌아온다: " + wallMs + "ms");
        assertEquals(1, stub.hits(HYPERVISORS), "상류를 다시 부르지 않는다");
    }

    @DisplayName("필드가 없거나 숫자가 아닌 하이퍼바이저는 그 행만 쓸 수 없다고 적는다")
    @Test
    void missing_or_non_numeric_field_marks_only_that_node() {
        stub.reply(HYPERVISORS, 200, "{\"hypervisors\":["
                + "{\"hypervisor_hostname\":\"h1\",\"state\":\"up\",\"status\":\"enabled\",\"memory_mb\":1024},"
                + "{\"hypervisor_hostname\":\"h2\",\"state\":\"up\",\"status\":\"enabled\",\"vcpus\":4,\"memory_mb\":\"abc\"},"
                + "{\"hypervisor_hostname\":\"h3\",\"state\":\"up\",\"status\":\"enabled\",\"vcpus\":\"8\",\"memory_mb\":2048}]}");

        NovaInventorySnapshot snapshot = gateway(5_000).read();

        assertEquals(InventoryStatus.OK, snapshot.status());
        assertEquals(List.of(false, false, true), snapshot.nodes().stream().map(Node::usable).toList());
        assertEquals("필드 누락 · 숫자 아님: vcpus", snapshot.nodes().get(0).skipReason());
        assertEquals("필드 누락 · 숫자 아님: memory_mb", snapshot.nodes().get(1).skipReason());
        assertEquals(8, snapshot.nodes().get(2).totalCpu(), "숫자 문자열은 읽는다");
    }

    @DisplayName("state 가 down 이거나 status 가 disabled 인 하이퍼바이저는 사유와 함께 쓸 수 없다고 적는다")
    @Test
    void down_or_disabled_hypervisor_is_not_usable() {
        stub.reply(HYPERVISORS, 200, "{\"hypervisors\":["
                + "{\"hypervisor_hostname\":\"h1\",\"state\":\"down\",\"status\":\"enabled\",\"vcpus\":4,\"memory_mb\":1024},"
                + "{\"hypervisor_hostname\":\"h2\",\"state\":\"up\",\"status\":\"disabled\",\"vcpus\":4,\"memory_mb\":1024}]}");

        NovaInventorySnapshot snapshot = gateway(5_000).read();

        assertEquals(List.of("state down", "status disabled"), snapshot.nodes().stream().map(Node::skipReason).toList());
        assertEquals(List.of(false, false), snapshot.nodes().stream().map(Node::usable).toList());
    }

    @DisplayName("같은 flavor 는 한 번만 읽고, flavor 하나를 못 읽으면 그 VM 만 변환하지 않는다")
    @Test
    void flavor_is_read_once_per_id_and_failure_marks_only_that_server() {
        stub.reply(SERVERS, 200, "{\"servers\":["
                + "{\"id\":\"s1\",\"name\":\"a\",\"status\":\"ACTIVE\",\"OS-EXT-SRV-ATTR:hypervisor_hostname\":\"h\",\"flavor\":{\"id\":\"f-small\"}},"
                + "{\"id\":\"s2\",\"name\":\"b\",\"status\":\"ACTIVE\",\"OS-EXT-SRV-ATTR:hypervisor_hostname\":\"h\",\"flavor\":{\"id\":\"f-small\"}},"
                + "{\"id\":\"s3\",\"name\":\"c\",\"status\":\"ACTIVE\",\"OS-EXT-SRV-ATTR:hypervisor_hostname\":\"h\",\"flavor\":{\"id\":\"f-small\"}},"
                + "{\"id\":\"s4\",\"name\":\"d\",\"status\":\"ACTIVE\",\"OS-EXT-SRV-ATTR:hypervisor_hostname\":\"h\",\"flavor\":{\"id\":\"f-missing\"}},"
                + "{\"id\":\"s5\",\"name\":\"e\",\"status\":\"ACTIVE\",\"OS-EXT-SRV-ATTR:hypervisor_hostname\":\"h\",\"flavor\":{\"original_name\":\"m1.tiny\",\"vcpus\":1,\"ram\":512,\"disk\":1}},"
                + "{\"id\":\"s6\",\"name\":\"f\",\"status\":\"ERROR\",\"OS-EXT-SRV-ATTR:hypervisor_hostname\":\"h\",\"flavor\":{\"id\":\"f-small\"}}]}");

        NovaInventorySnapshot snapshot = gateway(5_000).read();

        assertEquals(1, stub.hits(FLAVOR_SMALL), "같은 id 는 한 번만 읽는다");
        assertEquals(1, stub.hits("/v2.1/flavors/f-missing"));
        assertEquals(List.of(true, true, true, false, true, false), snapshot.servers().stream().map(Server::convertible).toList());
        Server missing = snapshot.servers().get(3);
        assertEquals("flavor 조회 실패: f-missing", missing.skipReason());
        assertNull(missing.requiredCpu());
        Server embedded = snapshot.servers().get(4);
        assertEquals("m1.tiny", embedded.flavorId(), "자원 값이 들어 있는 flavor 는 따로 읽지 않는다");
        assertEquals(512, embedded.requiredMemoryMb());
        assertEquals("상태 ERROR", snapshot.servers().get(5).skipReason());
        assertEquals(List.of("flavors"), snapshot.unavailable());
        assertEquals(InventoryStatus.OK, snapshot.status(), "flavor 실패는 전체 실패가 아니다");
    }

    @DisplayName("본문을 JSON 으로 읽을 수 없으면 목록 키 없음과 같은 MALFORMED 다")
    @Test
    void unreadable_json_body_is_malformed() {
        stub.reply(HYPERVISORS, 200, "<html>proxy error</html>");

        NovaInventorySnapshot snapshot = gateway(5_000).read();

        assertEquals(InventoryStatus.MALFORMED, snapshot.status(), "클라이언트가 본문 읽기 실패를 RestClientException 으로 감싼다");
        assertTrue(snapshot.nodes().isEmpty());
        assertFalse(snapshot.serversRead());
    }

    @DisplayName("한 번의 조회에서 따로 읽는 flavor 는 50종까지이고 넘은 flavor 를 쓰는 VM 만 변환하지 않는다")
    @Test
    void flavor_lookups_stop_at_fifty_kinds() {
        StringBuilder servers = new StringBuilder("{\"servers\":[");
        for (int i = 0; i <= NovaInventoryGateway.FLAVOR_LOOKUP_LIMIT; i++) {
            if (i > 0) servers.append(',');
            servers.append("{\"id\":\"s").append(i).append("\",\"name\":\"vm").append(i)
                    .append("\",\"status\":\"ACTIVE\",\"OS-EXT-SRV-ATTR:hypervisor_hostname\":\"h\",\"flavor\":{\"id\":\"f-").append(i).append("\"}}");
            stub.reply("/v2.1/flavors/f-" + i, 200, "{\"flavor\":{\"id\":\"f-" + i + "\",\"vcpus\":1,\"ram\":512,\"disk\":1}}");
        }
        stub.reply(SERVERS, 200, servers.append("]}").toString());

        NovaInventorySnapshot snapshot = gateway(15_000).read();

        String last = "f-" + NovaInventoryGateway.FLAVOR_LOOKUP_LIMIT;
        assertEquals(0, stub.hits("/v2.1/flavors/" + last), "51번째 종류는 읽지 않는다");
        assertEquals(1, stub.hits("/v2.1/flavors/f-0"));
        Server over = snapshot.servers().stream().filter(s -> last.equals(s.flavorId())).findFirst().orElseThrow();
        assertFalse(over.convertible());
        assertEquals("flavor 조회 상한 초과", over.skipReason());
        assertEquals(NovaInventoryGateway.FLAVOR_LOOKUP_LIMIT, snapshot.servers().stream().filter(Server::convertible).count());
        assertEquals(List.of("flavors"), snapshot.unavailable());
        assertEquals(InventoryStatus.OK, snapshot.status());
    }

    @DisplayName("가용영역 · VM 목록 질의가 실패해도 노드는 그대로 싣고 실패한 질의 이름만 적는다")
    @Test
    void partial_query_failure_keeps_nodes() {
        stub.reply(ZONES, 500, "{\"computeFault\":{}}");
        stub.reply(SERVERS, 403, "{\"forbidden\":{}}");

        NovaInventorySnapshot snapshot = gateway(5_000).read();

        assertEquals(InventoryStatus.OK, snapshot.status());
        assertEquals(List.of("availabilityZones", "servers"), snapshot.unavailable());
        assertEquals(2, snapshot.nodes().size());
        assertTrue(snapshot.nodes().stream().allMatch(n -> n.availabilityZone() == null && n.usable()));
        assertFalse(snapshot.serversRead(), "VM 건수를 모른다");
        assertEquals(List.of(), snapshot.servers());
    }

    private NovaInventoryGateway gateway(long timeoutMs) {
        return gateway(stub.base(), timeoutMs);
    }

    private NovaInventoryGateway gateway(String base, long timeoutMs) {
        OpenStackProperties props = new OpenStackProperties();
        props.setAuthUrl(base + "/v3");
        props.setComputeUrl(base + "/v2.1");
        props.setUsername("scheduler");
        props.setPassword("secret");
        props.setProjectName("admin");
        return gateway(props, timeoutMs);
    }

    private NovaInventoryGateway gateway(OpenStackProperties props, long timeoutMs) {
        NovaInventoryGateway gateway = new NovaInventoryGateway(new NovaClient(props, new KeystoneClient(props)), timeoutMs);
        gateways.add(gateway);
        return gateway;
    }

    private static String fixture(String name) {
        try (InputStream in = NovaInventoryGatewayTest.class.getResourceAsStream("/scheduler/inventory/" + name)) {
            assertNotNull(in, "픽스처 " + name);
            return new String(in.readAllBytes(), UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    record Reply(int status, String body, Map<String, String> headers, long delayMs) {}

    /** 경로별로 정한 응답을 돌려주는 Keystone · Nova 흉내 서버. 경로마다 받은 요청 수를 센다. */
    static final class Stub implements AutoCloseable {

        final Map<String, Reply> replies = new ConcurrentHashMap<>();
        private final Map<String, AtomicInteger> hits = new ConcurrentHashMap<>();
        private final ExecutorService pool = Executors.newCachedThreadPool();
        private final HttpServer server;

        Stub() throws IOException {
            server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
            server.createContext("/", this::handle);
            server.setExecutor(pool);
            server.start();
        }

        String base() {
            return "http://127.0.0.1:" + server.getAddress().getPort();
        }

        void token() {
            replies.put(TOKEN, new Reply(201, "{\"token\":{\"expires_at\":\"2099-01-01T00:00:00.000000Z\"}}",
                    Map.of("X-Subject-Token", "test-token"), 0));
        }

        void reply(String path, int status, String body) {
            reply(path, status, body, 0);
        }

        void reply(String path, int status, String body, long delayMs) {
            replies.put(path, new Reply(status, body, Map.of(), delayMs));
        }

        int hits(String path) {
            AtomicInteger count = hits.get(path);
            return count == null ? 0 : count.get();
        }

        private void handle(HttpExchange exchange) throws IOException {
            String path = exchange.getRequestURI().getPath();
            hits.computeIfAbsent(path, k -> new AtomicInteger()).incrementAndGet();
            Reply reply = replies.getOrDefault(path, new Reply(404, "{\"itemNotFound\":{\"code\":404}}", Map.of(), 0));
            if (reply.delayMs() > 0) {
                try {
                    Thread.sleep(reply.delayMs());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            byte[] body = reply.body().getBytes(UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            reply.headers().forEach((name, value) -> exchange.getResponseHeaders().add(name, value));
            exchange.sendResponseHeaders(reply.status(), body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        }

        @Override
        public void close() {
            server.stop(0);
            pool.shutdownNow();
        }
    }
}
