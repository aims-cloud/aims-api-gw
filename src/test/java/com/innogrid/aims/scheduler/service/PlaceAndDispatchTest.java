package com.innogrid.aims.scheduler.service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.innogrid.aims.scheduler.entity.SchedulingPolicy;
import com.innogrid.aims.scheduler.entity.Workload;
import com.innogrid.aims.scheduler.model.NodeResource;
import com.innogrid.aims.scheduler.model.PlacementContext;
import com.innogrid.aims.scheduler.model.SchedulingResult;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link PlaceAndDispatch} 의 예약·되돌림 순서와, 운영 코드에서 예약 되돌림({@link PlacementContext#unreserve})을 부르는 곳이
 * PlaceAndDispatch 뿐인지.
 *
 * <pre>
 * 노드      8코어·GPU 2·디스크 100GB 1대, CPU 10%
 * 워크로드  5코어·메모리 64MB·GPU 1·디스크 10GB
 * 결정      성공(그 노드) 또는 실패. 엔진 없이 람다로 준다
 * </pre>
 *
 * <p>스프링 컨텍스트 없이 돈다. 실행 루프·재생 수준의 동작은 DispatchRejectionReservationTest·
 * WorkloadChangeDuringRunTest·RunInputReplayTest 가 확인한다.</p>
 */
class PlaceAndDispatchTest {

    private static final String NODE = "pad-n8:9100";

    @DisplayName("실행 계층이 받은 배치는 예약이 남고 결과는 전달을 거친 결과다")
    @Test
    void accepted_placement_keeps_reservation() {
        NodeResource node = node();
        Workload w = workload();
        SchedulingResult ok = success();
        SchedulingResult delivered = success();

        PlaceAndDispatch.Done done = PlaceAndDispatch.run(context(node), w, () -> ok, p -> delivered);

        assertSame(ok, done.placed());
        assertSame(delivered, done.delivered());
        assertEquals(5, node.getReservedCpu());
        assertEquals(64, node.getReservedMemoryMb());
        assertEquals(1, node.getReservedGpu());
        assertEquals(10, node.reservedDiskGb());
    }

    @DisplayName("실행 계층이 받지 않은 배치는 예약을 잡은 뒤 네 축 모두 처음 값으로 돌아간다")
    @Test
    void rejected_placement_restores_all_axes() {
        NodeResource node = node();
        double cpu = node.availableCpu();
        long memory = node.availableMemoryMb();
        int gpu = node.availableGpu();
        long disk = node.availableDiskGb();
        int[] reservedAtDelivery = {-1};

        PlaceAndDispatch.Done done = PlaceAndDispatch.run(context(node), workload(), PlaceAndDispatchTest::success, p -> {
            reservedAtDelivery[0] = node.getReservedCpu();
            return SchedulingResult.failure("실행 계층 거부");
        });

        assertEquals(5, reservedAtDelivery[0], "되돌리기 전에는 예약이 잡혀 있었다");
        assertTrue(done.placed().success());
        assertFalse(done.delivered().success());
        assertEquals(0, node.getReservedCpu());
        assertEquals(0, node.getReservedMemoryMb());
        assertEquals(0, node.getReservedGpu());
        assertEquals(cpu, node.availableCpu());
        assertEquals(memory, node.availableMemoryMb());
        assertEquals(gpu, node.availableGpu());
        assertEquals(disk, node.availableDiskGb());
    }

    @DisplayName("실패한 결정은 노드가 적혀 있어도 예약 없이 전달만 한 번 거친다")
    @Test
    void failed_decision_reserves_nothing_and_delivers_once() {
        NodeResource node = node();
        // 노드를 가진 실패 결정. 성공일 때만 예약하는 조건이 빠지면 이 노드에 예약이 잡힌다
        SchedulingResult failed = new SchedulingResult(false, "mdc-01", NODE, 0, "자원 부족", List.of());
        AtomicInteger deliveries = new AtomicInteger();
        int[] reservedAtDelivery = {-1};

        PlaceAndDispatch.Done done = PlaceAndDispatch.run(context(node), workload(), () -> failed, p -> {
            deliveries.incrementAndGet();
            reservedAtDelivery[0] = node.getReservedCpu();
            assertSame(failed, p, "전달에는 결정을 그대로 넘긴다");
            return p;
        });

        assertEquals(1, deliveries.get());
        assertEquals(0, reservedAtDelivery[0]);
        assertSame(failed, done.delivered());
        assertEquals(0, node.getReservedCpu());
        assertEquals(0, node.getReservedGpu());
    }

    @DisplayName("전달이 예외를 내면 예약은 남고 예외는 그대로 나간다")
    @Test
    void delivery_exception_keeps_reservation_and_propagates() {
        NodeResource node = node();
        IllegalStateException boom = new IllegalStateException("전달 기록 저장 실패");

        IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> PlaceAndDispatch.run(context(node), workload(), PlaceAndDispatchTest::success, p -> { throw boom; }));

        assertSame(boom, thrown);
        assertEquals(5, node.getReservedCpu(), "커밋된 결정의 예약은 실행 후 스냅샷에 남는다. 되돌리면 안 된다");
        assertEquals(1, node.getReservedGpu());
    }

    @DisplayName("전달은 예약을 잡은 뒤에 불린다")
    @Test
    void delivery_sees_reservation() {
        NodeResource node = node();
        int[] reservedAtDelivery = {-1};

        PlaceAndDispatch.run(context(node), workload(), PlaceAndDispatchTest::success, p -> {
            reservedAtDelivery[0] = node.getReservedCpu();
            return p;
        });

        assertEquals(5, reservedAtDelivery[0]);
    }

    @DisplayName("운영 코드에서 예약 되돌림을 부르는 곳은 PlacementContext 본문과 PlaceAndDispatch 하나이고 실행 루프와 재생이 이를 거친다")
    @Test
    void only_place_and_dispatch_releases_reservations() throws IOException {
        Path root = Path.of("src", "main", "java", "com", "innogrid", "aims", "scheduler");
        Map<String, String> code = new TreeMap<>();
        try (Stream<Path> walk = Files.walk(root)) {
            for (Path p : walk.filter(f -> f.toString().endsWith(".java")).toList()) {
                code.put(root.relativize(p).toString().replace('\\', '/'), withoutComments(Files.readString(p, StandardCharsets.UTF_8)));
            }
        }
        assertTrue(code.size() > 100, "스케줄러 운영 소스를 찾지 못했다. 작업 디렉터리가 프로젝트 루트인가: " + root.toAbsolutePath());

        Pattern release = Pattern.compile("\\.\\s*unreserve\\s*\\(|::\\s*unreserve\\b");
        Map<String, Long> callers = new TreeMap<>();
        code.forEach((file, text) -> {
            long n = release.matcher(text).results().count();
            if (n > 0) callers.put(file, n);
        });
        assertEquals(Map.of("model/PlacementContext.java", 1L, "service/PlaceAndDispatch.java", 1L), callers,
                "예약 되돌림 호출은 PlacementContext.unreserve 본문(노드로 넘김)과 PlaceAndDispatch 한 번뿐이어야 한다");

        assertTrue(code.get("service/ScheduleExecutionService.java").contains("PlaceAndDispatch.run("), "실행 루프가 PlaceAndDispatch 를 거쳐야 한다");
        assertTrue(code.get("service/RunInputReplay.java").contains("PlaceAndDispatch.run("), "재생이 PlaceAndDispatch 를 거쳐야 한다");

        String own = code.get("service/PlaceAndDispatch.java");
        for (String bean : List.of("@Service", "@Component", "@Transactional", "TransactionTemplate")) {
            assertFalse(own.contains(bean), "PlaceAndDispatch 는 빈이 아니고 트랜잭션을 열지 않는다: " + bean);
        }
    }

    /**
     * 문자열·텍스트 블록·문자 리터럴은 그대로 두고 블록 주석과 줄 주석만 지운다. 유니코드 이스케이프는 풀지 않는다.
     */
    static String withoutComments(String src) {
        StringBuilder out = new StringBuilder(src.length());
        int i = 0;
        int n = src.length();
        while (i < n) {
            if (src.startsWith("\"\"\"", i)) {
                int end = i + 3;
                while (true) {
                    end = src.indexOf("\"\"\"", end);
                    if (end < 0) {
                        end = n;
                        break;
                    }
                    int backslashes = 0;
                    while (end - 1 - backslashes >= i + 3 && src.charAt(end - 1 - backslashes) == '\\') backslashes++;
                    if (backslashes % 2 == 0) {
                        end += 3;
                        break;
                    }
                    end++;
                }
                out.append(src, i, end);
                i = end;
            } else if (src.charAt(i) == '"' || src.charAt(i) == '\'') {
                char quote = src.charAt(i);
                int j = i + 1;
                while (j < n && src.charAt(j) != quote) j += src.charAt(j) == '\\' ? 2 : 1;
                int end = Math.min(n, j + 1);
                out.append(src, i, end);
                i = end;
            } else if (src.startsWith("/*", i)) {
                int end = src.indexOf("*/", i + 2);
                i = end < 0 ? n : end + 2;
                out.append(' ');
            } else if (src.startsWith("//", i)) {
                int end = src.indexOf('\n', i);
                i = end < 0 ? n : end;
            } else {
                out.append(src.charAt(i++));
            }
        }
        return out.toString();
    }

    @DisplayName("주석 지우기는 리터럴 안의 주석 표시와 이스케이프된 따옴표를 남기고 주석 안의 호출만 지운다")
    @Test
    void comment_stripper_keeps_literals() {
        String src = "a(\"//x\"); /* b.unreserve(1) */ c('/'); // d.unreserve(2)\n"
                + "e(\"\"\"\n /* not a comment */ \\\"\"\" still text\n\"\"\"); f.unreserve(3);\n"
                + "g(\"q\\\"//y\"); h('\"'); /* i.unreserve(4) */ // don't\n"
                + "j.unreserve(5);";

        String code = withoutComments(src);

        assertTrue(code.contains("\"//x\""));
        assertTrue(code.contains("'/'"));
        assertTrue(code.contains("/* not a comment */"));
        assertTrue(code.contains("\\\"\"\" still text"), "텍스트 블록 안의 이스케이프된 따옴표 셋은 블록을 닫지 않는다");
        assertTrue(code.contains("f.unreserve(3)"));
        assertTrue(code.contains("\"q\\\"//y\""), "문자열 안의 이스케이프된 따옴표 뒤 // 는 주석이 아니다");
        assertTrue(code.contains("'\"'"), "문자 리터럴의 따옴표가 문자열을 열지 않는다");
        assertTrue(code.contains("j.unreserve(5)"), "주석 속 아포스트로피가 문자 리터럴을 열지 않는다");
        assertFalse(code.contains("b.unreserve(1)"));
        assertFalse(code.contains("d.unreserve(2)"));
        assertFalse(code.contains("i.unreserve(4)"));
        assertFalse(code.contains("don't"));
    }

    private static NodeResource node() {
        return new NodeResource("mdc-01", NODE, 8, 65536, 2, 10, 20, 0, 0, 500, 0, 0, 0).withDiskCapacityGb(100);
    }

    private static PlacementContext context(NodeResource node) {
        return PlacementContext.of(List.of(node), new SchedulingPolicy("pad-p", "d"));
    }

    private static Workload workload() {
        Workload w = new Workload("pad-w", 5, 64);
        w.setRequiredGpu(1);
        w.setRequiredDiskGb(10);
        return w;
    }

    private static SchedulingResult success() {
        return new SchedulingResult(true, "mdc-01", NODE, 0.9, "배치", List.of());
    }
}
