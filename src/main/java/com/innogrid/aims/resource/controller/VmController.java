package com.innogrid.aims.resource.controller;

import java.util.Map;

import com.innogrid.aims.cloudbarista.spider.SpiderClient;
import com.innogrid.aims.cloudbarista.tumblebug.TumblebugClient;
import com.innogrid.aims.common.dto.ApiResponse;
import com.innogrid.aims.resource.dto.ResourceDto;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.*;

/**
 * VM 라이프사이클 관리 API.
 * [요구사항 #18] 가상자원 생성·시작·정지·재기동·삭제
 * 퍼블릭 클라우드: CB-Spider(단일 VM) / CB-Tumblebug(멀티클라우드 인프라)
 */
@Tag(name = "VM 관리", description = "VM 라이프사이클 (CB-Spider/Tumblebug)")
@RestController
@RequestMapping("/api/v1/vms")
public class VmController {

    private final SpiderClient spiderClient;
    private final TumblebugClient tumblebugClient;

    public VmController(SpiderClient spiderClient, TumblebugClient tumblebugClient) {
        this.spiderClient = spiderClient;
        this.tumblebugClient = tumblebugClient;
    }

    @GetMapping
    public ApiResponse<?> listVms(@RequestParam(required = false) String connectionName,
                                  @RequestParam(required = false) String nsId) {
        if (nsId != null) {
            return ApiResponse.ok(tumblebugClient.listInfra(nsId));
        }
        if (connectionName != null) {
            return ApiResponse.ok(spiderClient.listVMs(connectionName));
        }
        return ApiResponse.ok(null);
    }

    @GetMapping("/{vmName}")
    public ApiResponse<?> getVm(@PathVariable String vmName,
                                @RequestParam String connectionName) {
        return ApiResponse.ok(spiderClient.getVM(connectionName, vmName));
    }

    @GetMapping("/{vmName}/status")
    public ApiResponse<?> getVmStatus(@PathVariable String vmName,
                                      @RequestParam String connectionName) {
        return ApiResponse.ok(spiderClient.getVMStatus(connectionName, vmName));
    }

    @PostMapping
    @io.swagger.v3.oas.annotations.parameters.RequestBody(
            content = @Content(schema = @Schema(implementation = ResourceDto.CreateVmRequest.class)))
    public ApiResponse<?> createVm(@RequestBody Map<String, Object> request) {
        return ApiResponse.ok(spiderClient.createVm(request));
    }

    @PostMapping("/infra")
    @io.swagger.v3.oas.annotations.parameters.RequestBody(
            content = @Content(schema = @Schema(implementation = ResourceDto.CreateInfraRequest.class)))
    public ApiResponse<?> createMultiCloudInfra(@RequestParam String nsId,
                                                @RequestBody Map<String, Object> request) {
        return ApiResponse.ok(tumblebugClient.createInfra(nsId, request));
    }

    @PostMapping("/{vmName}/control")
    public ApiResponse<?> controlVm(@PathVariable String vmName,
                                    @RequestParam String connectionName,
                                    @RequestParam String action) {
        return ApiResponse.ok(spiderClient.controlVM(connectionName, vmName, action));
    }

    @DeleteMapping("/{vmName}")
    public ApiResponse<Void> deleteVm(@PathVariable String vmName,
                                      @RequestParam String connectionName) {
        spiderClient.deleteVM(connectionName, vmName);
        return ApiResponse.ok(null);
    }
}
