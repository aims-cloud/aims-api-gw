package com.innogrid.aims.resource.controller;

import java.util.Map;

import com.innogrid.aims.common.dto.ApiResponse;
import com.innogrid.aims.common.exception.BusinessException;
import com.innogrid.aims.resource.entity.CatalogItem;
import com.innogrid.aims.resource.entity.ResourceRequest;
import com.innogrid.aims.resource.repository.CatalogItemRepository;
import com.innogrid.aims.resource.repository.ResourceRequestRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@Tag(name = "카탈로그", description = "자원 요청/승인")
@RestController
@RequestMapping("/api/v1/catalogs")
public class CatalogController {

    private final CatalogItemRepository catalogRepository;
    private final ResourceRequestRepository requestRepository;

    public CatalogController(CatalogItemRepository catalogRepository,
                             ResourceRequestRepository requestRepository) {
        this.catalogRepository = catalogRepository;
        this.requestRepository = requestRepository;
    }

    @Operation(summary = "카탈로그 항목 목록")
    @GetMapping
    public ApiResponse<?> listCatalogItems() {
        return ApiResponse.ok(catalogRepository.findByActiveTrueOrderByCreatedAtDesc());
    }

    @Operation(summary = "카탈로그 항목 등록")
    @PostMapping
    public ApiResponse<?> createCatalogItem(@RequestBody Map<String, Object> request) {
        CatalogItem item = new CatalogItem(
                (String) request.get("name"),
                (String) request.get("description"),
                (String) request.getOrDefault("category", "COMPUTE"));
        if (request.containsKey("cpu")) item.setCpu((int) request.get("cpu"));
        if (request.containsKey("memoryGb")) item.setMemoryGb((int) request.get("memoryGb"));
        if (request.containsKey("diskGb")) item.setDiskGb((int) request.get("diskGb"));
        if (request.containsKey("os")) item.setOs((String) request.get("os"));
        if (request.containsKey("gpu")) item.setGpu((String) request.get("gpu"));
        if (request.containsKey("monthlyPrice")) item.setMonthlyPrice((int) request.get("monthlyPrice"));
        return ApiResponse.ok(catalogRepository.save(item));
    }

    @Operation(summary = "카탈로그 항목 수정")
    @PutMapping("/{catalogId}")
    public ApiResponse<?> updateCatalogItem(@PathVariable Long catalogId,
                                            @RequestBody Map<String, Object> request) {
        CatalogItem item = catalogRepository.findById(catalogId)
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND, "카탈로그 항목을 찾을 수 없습니다"));
        if (request.containsKey("name")) item.setName((String) request.get("name"));
        if (request.containsKey("description")) item.setDescription((String) request.get("description"));
        if (request.containsKey("cpu")) item.setCpu((int) request.get("cpu"));
        if (request.containsKey("memoryGb")) item.setMemoryGb((int) request.get("memoryGb"));
        if (request.containsKey("diskGb")) item.setDiskGb((int) request.get("diskGb"));
        if (request.containsKey("monthlyPrice")) item.setMonthlyPrice((int) request.get("monthlyPrice"));
        return ApiResponse.ok(catalogRepository.save(item));
    }

    @Operation(summary = "자원 요청 (사용자)")
    @PostMapping("/{catalogId}/requests")
    public ApiResponse<?> requestResource(@PathVariable Long catalogId,
                                          @RequestBody Map<String, Object> request) {
        CatalogItem item = catalogRepository.findById(catalogId)
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND, "카탈로그 항목을 찾을 수 없습니다"));
        ResourceRequest rr = new ResourceRequest(item,
                request.containsKey("requesterId") ? ((Number) request.get("requesterId")).longValue() : null,
                (String) request.get("requesterName"),
                (String) request.get("reason"));
        return ApiResponse.ok(requestRepository.save(rr));
    }

    @Operation(summary = "자원 요청 목록 (관리자)")
    @GetMapping("/requests")
    public ApiResponse<?> listResourceRequests(@RequestParam(required = false) String status) {
        if (status != null) {
            return ApiResponse.ok(requestRepository.findByStatusOrderByRequestedAtDesc(
                    ResourceRequest.RequestStatus.valueOf(status.toUpperCase())));
        }
        return ApiResponse.ok(requestRepository.findAllByOrderByRequestedAtDesc());
    }

    @Operation(summary = "자원 요청 승인/반려")
    @PostMapping("/requests/{requestId}/approve")
    public ApiResponse<?> approveRequest(@PathVariable Long requestId,
                                         @RequestParam boolean approved,
                                         @RequestParam(required = false) String comment) {
        ResourceRequest rr = requestRepository.findById(requestId)
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND, "요청을 찾을 수 없습니다"));
        if (approved) {
            rr.approve(comment);
        } else {
            rr.reject(comment);
        }
        return ApiResponse.ok(requestRepository.save(rr));
    }
}
