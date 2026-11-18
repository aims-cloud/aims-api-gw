package com.innogrid.aims.scheduler.dto;

import java.time.OffsetDateTime;

import com.innogrid.aims.scheduler.entity.MdcPowerBudget;

/** MDC 전력 예산 응답 — 11필드. 필드 구성은 {@code MdcPowerBudgetApiTest}가 확인한다. */
public record MdcPowerBudgetResponse(
        Long id,
        String mdcId,
        double budgetWatts,
        MdcPowerBudget.BudgetSource source,
        String description,
        String createdBy,
        String createdByName,
        String updatedBy,
        String updatedByName,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
) {
    public static MdcPowerBudgetResponse from(MdcPowerBudget b) {
        return new MdcPowerBudgetResponse(b.getId(), b.getMdcId(), b.getBudgetWatts(), b.getSource(), b.getDescription(),
                b.getCreatedBy(), b.getCreatedByName(), b.getUpdatedBy(), b.getUpdatedByName(),
                b.getCreatedAt(), b.getUpdatedAt());
    }
}
