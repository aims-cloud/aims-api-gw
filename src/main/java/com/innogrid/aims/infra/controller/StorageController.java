package com.innogrid.aims.infra.controller;

import java.util.Map;

import com.innogrid.aims.cloudbarista.spider.SpiderClient;
import com.innogrid.aims.common.dto.ApiResponse;
import com.innogrid.aims.resource.dto.ResourceDto;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.*;

/**
 * 스토리지(디스크/볼륨) 관리 API.
 * [요구사항 #27] 볼륨 관리 — 퍼블릭 클라우드 디스크는 CB-Spider 위임
 * [요구사항 #28] 물리 디스크 — MDC 자체 (향후 구현)
 * [요구사항 #29] 용량 사용량 — VictoriaMetrics 연동 (향후)
 */
@Tag(name = "스토리지", description = "디스크/볼륨 관리 (CB-Spider)")
@RestController
@RequestMapping("/api/v1/storage")
public class StorageController {

    private final SpiderClient spiderClient;

    public StorageController(SpiderClient spiderClient) {
        this.spiderClient = spiderClient;
    }

    @GetMapping("/volumes")
    public ApiResponse<?> listVolumes(@RequestParam String connectionName) {
        return ApiResponse.ok(spiderClient.listDisks(connectionName));
    }

    @GetMapping("/volumes/{diskName}")
    public ApiResponse<?> getVolume(@PathVariable String diskName,
                                    @RequestParam String connectionName) {
        return ApiResponse.ok(spiderClient.getDisk(connectionName, diskName));
    }

    @PostMapping("/volumes")
    @io.swagger.v3.oas.annotations.parameters.RequestBody(
            content = @Content(schema = @Schema(implementation = ResourceDto.CreateDiskRequest.class)))
    public ApiResponse<?> createVolume(@RequestBody Map<String, Object> request) {
        return ApiResponse.ok(spiderClient.createDisk(request));
    }

    @PutMapping("/volumes/{diskName}/resize")
    @io.swagger.v3.oas.annotations.parameters.RequestBody(
            content = @Content(schema = @Schema(implementation = ResourceDto.ResizeDiskRequest.class)))
    public ApiResponse<?> resizeVolume(@PathVariable String diskName,
                                       @RequestBody Map<String, Object> request) {
        return ApiResponse.ok(spiderClient.resizeDisk(diskName, request));
    }

    @PutMapping("/volumes/{diskName}/attach")
    @io.swagger.v3.oas.annotations.parameters.RequestBody(
            content = @Content(schema = @Schema(implementation = ResourceDto.AttachDiskRequest.class)))
    public ApiResponse<?> attachVolume(@PathVariable String diskName,
                                       @RequestBody Map<String, Object> request) {
        return ApiResponse.ok(spiderClient.attachDisk(diskName, request));
    }

    @PutMapping("/volumes/{diskName}/detach")
    @io.swagger.v3.oas.annotations.parameters.RequestBody(
            content = @Content(schema = @Schema(implementation = ResourceDto.AttachDiskRequest.class)))
    public ApiResponse<?> detachVolume(@PathVariable String diskName,
                                       @RequestBody Map<String, Object> request) {
        return ApiResponse.ok(spiderClient.detachDisk(diskName, request));
    }

    @DeleteMapping("/volumes/{diskName}")
    public ApiResponse<Void> deleteVolume(@PathVariable String diskName,
                                          @RequestParam String connectionName) {
        spiderClient.deleteDisk(connectionName, diskName);
        return ApiResponse.ok(null);
    }
}
