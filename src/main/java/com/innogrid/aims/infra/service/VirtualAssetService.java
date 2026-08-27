package com.innogrid.aims.infra.service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.innogrid.aims.cloudbarista.spider.SpiderClient;

/**
 * 가상화 자산 서비스.
 * CB-Spider(바리스타)로 연동한 퍼블릭 클라우드 자원을 호스트 → VM 계층으로 표출한다.
 * 호스트 = CB-Spider 연결(배포영역/리전), 하위 = 해당 연결의 VM 목록.
 */
@Service
public class VirtualAssetService {

    private static final Logger log = LoggerFactory.getLogger(VirtualAssetService.class);

    private final SpiderClient spiderClient;

    public VirtualAssetService(SpiderClient spiderClient) {
        this.spiderClient = spiderClient;
    }

    /**
     * 호스트(배포영역) 목록 — CB-Spider 연결별 VM 수 집계.
     * 연결 단위 조회 실패는 해당 호스트의 vmCount=null 로 표시하고 전체 응답은 유지한다.
     */
    public List<Map<String, Object>> listHosts() {
        List<Map<String, Object>> connections =
                extractList(spiderClient.listConnectionConfigs(), "connectionconfig");

        List<Map<String, Object>> hosts = new ArrayList<>(connections.size());
        for (Map<String, Object> connection : connections) {
            String connectionName = string(connection.get("ConfigName"));
            Map<String, Object> host = new LinkedHashMap<>();
            host.put("connectionName", connectionName);
            host.put("providerName", connection.get("ProviderName"));
            host.put("regionName", connection.get("RegionName"));
            host.put("driverName", connection.get("DriverName"));
            host.put("vmCount", countVms(connectionName));
            hosts.add(host);
        }
        return hosts;
    }

    /** 호스트(연결) 하위 VM 목록 — 계층 확장 시 조회. */
    public List<Map<String, Object>> listHostVms(String connectionName) {
        List<Map<String, Object>> vms = extractList(spiderClient.listVMs(connectionName), "vm");
        List<Map<String, Object>> result = new ArrayList<>(vms.size());
        for (Map<String, Object> vm : vms) {
            result.add(toVmSummary(connectionName, vm));
        }
        return result;
    }

    private Integer countVms(String connectionName) {
        if (connectionName == null) return null;
        try {
            return extractList(spiderClient.listVMs(connectionName), "vm").size();
        } catch (Exception e) {
            log.warn("VM 수 집계 실패 (connection: {}): {}", connectionName, e.getMessage());
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> toVmSummary(String connectionName, Map<String, Object> vm) {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("connectionName", connectionName);

        Object iid = vm.get("IId");
        if (iid instanceof Map<?, ?> iidMap) {
            summary.put("vmName", ((Map<String, Object>) iidMap).get("NameId"));
        } else {
            summary.put("vmName", vm.get("Name"));
        }
        summary.put("vmSpecName", vm.get("VMSpecName"));
        summary.put("imageName", extractNameId(vm.get("ImageIId")));
        summary.put("vpcName", extractNameId(vm.get("VpcIID")));
        summary.put("subnetName", extractNameId(vm.get("SubnetIID")));
        summary.put("publicIp", vm.get("PublicIP"));
        summary.put("privateIp", vm.get("PrivateIP"));
        summary.put("startTime", vm.get("StartTime"));

        Object region = vm.get("Region");
        if (region instanceof Map<?, ?> regionMap) {
            summary.put("region", ((Map<String, Object>) regionMap).get("Region"));
            summary.put("zone", ((Map<String, Object>) regionMap).get("Zone"));
        }
        return summary;
    }

    @SuppressWarnings("unchecked")
    private static Object extractNameId(Object iid) {
        if (iid instanceof Map<?, ?> map) {
            return ((Map<String, Object>) map).get("NameId");
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> extractList(Map<String, Object> response, String key) {
        if (response == null) return Collections.emptyList();
        Object value = response.get(key);
        if (value instanceof List<?> list) {
            return (List<Map<String, Object>>) list;
        }
        return Collections.emptyList();
    }

    private static String string(Object value) {
        return value == null ? null : value.toString();
    }
}
