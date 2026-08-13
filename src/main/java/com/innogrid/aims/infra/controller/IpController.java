package com.innogrid.aims.infra.controller;

import java.util.List;
import java.util.Map;

import com.innogrid.aims.common.dto.ApiResponse;
import com.innogrid.aims.infra.entity.IpAllocation;
import com.innogrid.aims.infra.repository.IpAllocationRepository;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.*;

/**
 * IP 관리 API.
 * [요구사항 #35] IP 관리 — MDC·퍼블릭 클라우드 연계 구간 IP 자원 할당·대역 통합 관리
 */
@Tag(name = "IP 관리", description = "IP 할당/회수")
@RestController
@RequestMapping("/api/v1/ips")
public class IpController {

    private final IpAllocationRepository ipAllocationRepository;

    public IpController(IpAllocationRepository ipAllocationRepository) {
        this.ipAllocationRepository = ipAllocationRepository;
    }

    /** IP 할당 현황 조회 */
    @GetMapping
    public ApiResponse<List<IpAllocation>> listIpAllocations(@RequestParam(required = false) String mdcId) {
        List<IpAllocation> allocations;
        if (mdcId != null) {
            allocations = ipAllocationRepository.findByMdcId(mdcId);
        } else {
            allocations = ipAllocationRepository.findAll();
        }
        return ApiResponse.ok(allocations);
    }

    /** IP 할당 */
    @PostMapping
    public ApiResponse<IpAllocation> allocateIp(@RequestBody Map<String, Object> request) {
        String mdcId = (String) request.get("mdcId");
        String address = (String) request.get("address");
        String subnet = (String) request.get("subnet");
        String type = (String) request.get("type");

        IpAllocation allocation = new IpAllocation(mdcId, address, subnet, type);
        if (request.containsKey("assignedTo")) {
            allocation.setAssignedTo((String) request.get("assignedTo"));
        }
        if (request.containsKey("status")) {
            allocation.setStatus(IpAllocation.IpStatus.valueOf(((String) request.get("status")).toUpperCase()));
        }
        ipAllocationRepository.save(allocation);
        return ApiResponse.ok(allocation);
    }

    /** IP 회수 */
    @DeleteMapping("/{ipId}")
    public ApiResponse<Void> releaseIp(@PathVariable Long ipId) {
        ipAllocationRepository.deleteById(ipId);
        return ApiResponse.ok(null);
    }

    /** IP 대역 정보 조회 */
    @GetMapping("/subnets")
    public ApiResponse<List<IpAllocation>> listSubnets(@RequestParam(required = false) String mdcId) {
        List<IpAllocation> allocations;
        if (mdcId != null) {
            allocations = ipAllocationRepository.findByMdcId(mdcId);
        } else {
            allocations = ipAllocationRepository.findAll();
        }
        return ApiResponse.ok(allocations);
    }
}
