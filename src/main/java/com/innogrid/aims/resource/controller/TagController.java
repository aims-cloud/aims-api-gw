package com.innogrid.aims.resource.controller;

import java.util.Map;

import com.innogrid.aims.cloudbarista.tumblebug.TumblebugClient;
import com.innogrid.aims.common.dto.ApiResponse;
import com.innogrid.aims.resource.dto.ResourceDto;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.*;

/**
 * 태깅 API → CB-Tumblebug Label API 위임.
 * [요구사항 #22] 분산 가상자원에 태그를 부여하여 분류·검색·관리
 */
@Tag(name = "태깅", description = "자원 레이블 관리 (CB-Tumblebug)")
@RestController
@RequestMapping("/api/v1/tags")
public class TagController {

    private final TumblebugClient tumblebugClient;

    public TagController(TumblebugClient tumblebugClient) {
        this.tumblebugClient = tumblebugClient;
    }

    @GetMapping("/{labelType}/{uid}")
    public ApiResponse<?> getLabel(@PathVariable String labelType,
                                   @PathVariable String uid) {
        return ApiResponse.ok(tumblebugClient.getLabel(labelType, uid));
    }

    @PutMapping("/{labelType}/{uid}")
    @io.swagger.v3.oas.annotations.parameters.RequestBody(
            content = @Content(schema = @Schema(implementation = ResourceDto.SetLabelRequest.class)))
    public ApiResponse<?> setLabel(@PathVariable String labelType,
                                   @PathVariable String uid,
                                   @RequestBody Map<String, Object> request) {
        return ApiResponse.ok(tumblebugClient.setLabel(labelType, uid, request));
    }

    @DeleteMapping("/{labelType}/{uid}/{key}")
    public ApiResponse<Void> deleteLabel(@PathVariable String labelType,
                                         @PathVariable String uid,
                                         @PathVariable String key) {
        tumblebugClient.deleteLabel(labelType, uid, key);
        return ApiResponse.ok(null);
    }
}
