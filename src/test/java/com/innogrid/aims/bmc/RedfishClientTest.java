package com.innogrid.aims.bmc;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.innogrid.aims.bmc.BmcProperties.Endpoint;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Redfish ComputerSystem → {@link RedfishSystem} 정규화 검증.
 * XCC(Lenovo)·IDRAC(Dell) 실제 응답 형태를 표본으로 공통 매핑을 확인한다.
 */
class RedfishClientTest {

    private static Endpoint endpoint(String id) {
        Endpoint ep = new Endpoint();
        ep.setId(id);
        return ep;
    }

    @Test
    void normalizes_xcc_lenovo_system() {
        // 실제 XCC 응답 형태: Manufacturer="OEM", CoreCount 미제공(null), LogicalProcessorCount 만 제공
        Map<String, Object> sys = new java.util.HashMap<>();
        sys.put("Manufacturer", "OEM");
        sys.put("Model", "OPTIMUSiT L650");
        sys.put("SerialNumber", "J90205WB");
        sys.put("HostName", "XCC-7D76-J90205WB");
        sys.put("PowerState", "On");
        sys.put("Status", Map.of("Health", "OK", "State", "Enabled"));
        Map<String, Object> cpu = new java.util.HashMap<>();
        cpu.put("Count", 2);
        cpu.put("Model", "Intel(R) Xeon(R) Gold 5416S");
        cpu.put("CoreCount", null);                 // XCC 는 코어 수를 주지 않는다
        cpu.put("LogicalProcessorCount", 64);
        sys.put("ProcessorSummary", cpu);
        sys.put("MemorySummary", Map.of("TotalSystemMemoryGiB", 192));

        RedfishSystem r = RedfishClient.normalize(endpoint("aio01"), sys);

        assertThat(r.endpointId()).isEqualTo("aio01");
        assertThat(r.manufacturer()).isEqualTo("OEM");
        assertThat(r.model()).isEqualTo("OPTIMUSiT L650");
        assertThat(r.serialNumber()).isEqualTo("J90205WB");
        assertThat(r.powerState()).isEqualTo("On");
        assertThat(r.health()).isEqualTo("OK");
        assertThat(r.cpuModel()).contains("5416S");
        assertThat(r.cpuCount()).isEqualTo(2);
        assertThat(r.coreCount()).isNull();          // XCC → null
        assertThat(r.threadCount()).isEqualTo(64);
        assertThat(r.memoryGiB()).isEqualTo(192);
    }

    @Test
    void normalizes_idrac_dell_system_with_decimal_memory() {
        Map<String, Object> sys = Map.of(
                "Manufacturer", "Dell Inc.",
                "Model", "PowerEdge R760xa",
                "SerialNumber", "CNIVC0058J0004",
                "PowerState", "On",
                "Status", Map.of("Health", "OK"),
                "ProcessorSummary", Map.of(
                        "Count", 2,
                        "Model", "Intel(R) Xeon(R) Gold 6548Y+",
                        "LogicalProcessorCount", 128),
                // IDRAC 은 GiB 를 소수로 주기도 한다
                "MemorySummary", Map.of("TotalSystemMemoryGiB", 1024.0));

        RedfishSystem r = RedfishClient.normalize(endpoint("gpu02"), sys);

        assertThat(r.model()).isEqualTo("PowerEdge R760xa");
        assertThat(r.serialNumber()).isEqualTo("CNIVC0058J0004");
        assertThat(r.threadCount()).isEqualTo(128);
        assertThat(r.coreCount()).isNull();          // CoreCount 미제공 → null
        assertThat(r.hostName()).isNull();           // HostName 미설정 → null
        assertThat(r.memoryGiB()).isEqualTo(1024);
    }

    @Test
    void first_member_path_follows_systems_collection() {
        Map<String, Object> collection = Map.of(
                "Members", List.of(Map.of("@odata.id", "/redfish/v1/Systems/System.Embedded.1")));

        assertThat(RedfishClient.firstMemberPath(collection))
                .isEqualTo("/redfish/v1/Systems/System.Embedded.1");
        assertThat(RedfishClient.firstMemberPath(Map.of("Members", List.of()))).isNull();
        assertThat(RedfishClient.firstMemberPath(null)).isNull();
    }
}
