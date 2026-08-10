package com.innogrid.aims.resource.controller;

import java.util.Map;

import com.innogrid.aims.cloudbarista.spider.SpiderClient;
import com.innogrid.aims.common.dto.ApiResponse;
import com.innogrid.aims.resource.dto.ResourceDto;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.*;

/**
 * 이미지/스냅샷 관리 API.
 * [요구사항 #21] 이미지/템플릿 — 퍼블릭 클라우드 이미지는 CB-Spider 위임
 * [요구사항 #19] 스냅샷 — CB-Spider MyImage API 위임
 */
@Tag(name = "이미지/스냅샷", description = "이미지 조회, 스냅샷 관리 (CB-Spider)")
@RestController
@RequestMapping("/api/v1/images")
public class ImageController {

    private final SpiderClient spiderClient;

    public ImageController(SpiderClient spiderClient) {
        this.spiderClient = spiderClient;
    }

    @GetMapping
    public ApiResponse<?> listImages(@RequestParam String connectionName) {
        return ApiResponse.ok(spiderClient.listImages(connectionName));
    }

    @GetMapping("/{imageName}")
    public ApiResponse<?> getImage(@PathVariable String imageName,
                                   @RequestParam String connectionName) {
        return ApiResponse.ok(spiderClient.getImage(connectionName, imageName));
    }

    @GetMapping("/snapshots")
    public ApiResponse<?> listSnapshots(@RequestParam String connectionName) {
        return ApiResponse.ok(spiderClient.listMyImages(connectionName));
    }

    @PostMapping("/snapshots")
    @io.swagger.v3.oas.annotations.parameters.RequestBody(
            content = @Content(schema = @Schema(implementation = ResourceDto.CreateSnapshotRequest.class)))
    public ApiResponse<?> createSnapshot(@RequestBody Map<String, Object> request) {
        return ApiResponse.ok(spiderClient.createMyImage(request));
    }

    @DeleteMapping("/snapshots/{imageName}")
    public ApiResponse<Void> deleteSnapshot(@PathVariable String imageName,
                                            @RequestParam String connectionName) {
        spiderClient.deleteMyImage(connectionName, imageName);
        return ApiResponse.ok(null);
    }
}
