package com.innogrid.aims.scheduler.service;

import java.util.List;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.innogrid.aims.common.exception.BusinessException;
import com.innogrid.aims.scheduler.dto.MdcPowerBudgetRequest;
import com.innogrid.aims.scheduler.entity.MdcPowerBudget;
import com.innogrid.aims.scheduler.repository.MdcPowerBudgetRepository;

import tools.jackson.databind.JsonNode;

/**
 * MDC 전력 예산 원장 — 검증·저장 규칙.
 *
 * <p>거부하는 값은 "저장하면 확실히 틀린 것"이다(전력 사양·링크 원장과 같은 원칙) — 빈 MDC 식별자,
 * 0 이하 예산, NaN·무한대 예산, 모르는 출처. <b>예산의 크기 상한은 일부러 두지 않는다.</b> 실데이터가 없는
 * 지금 상한은 추측이라 정당한 규모를 막을 수 있고, 값이 이상하면 진단의 MDC 요약에 그대로 드러난다.</p>
 *
 * <p>MDC 식별자 중복은 DB 유니크 제약이 잡지만 그때는 500으로 새므로 먼저 조회해 409를 낸다.</p>
 */
@Service
public class MdcPowerBudgetService {

    private final MdcPowerBudgetRepository repository;
    private final ScheduleActorResolver actorResolver;

    public MdcPowerBudgetService(MdcPowerBudgetRepository repository, ScheduleActorResolver actorResolver) {
        this.repository = repository;
        this.actorResolver = actorResolver;
    }

    // ── 조회 ───────────────────────────────────────────────────

    /** 목록. {@code mdcId}를 주면 그 문자열을 포함하는 MDC만(부분 일치, 대소문자 무시). */
    @Transactional(readOnly = true)
    public List<MdcPowerBudget> list(String mdcId) {
        List<MdcPowerBudget> all = repository.findAllByOrderByMdcIdAsc();
        if (mdcId == null || mdcId.isBlank()) return all;
        String needle = mdcId.trim().toLowerCase();
        return all.stream().filter(b -> b.getMdcId().toLowerCase().contains(needle)).toList();
    }

    /** 없으면 404. */
    @Transactional(readOnly = true)
    public MdcPowerBudget require(Long id) {
        return repository.findById(id)
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND, "MDC 전력 예산을 찾을 수 없습니다: " + id));
    }

    // ── 변경 ───────────────────────────────────────────────────

    @Transactional
    public MdcPowerBudget create(MdcPowerBudgetRequest r) {
        if (r == null) throw badRequest("요청 본문이 필요합니다");
        String mdcId = requireText(r.mdcId(), "MDC 식별자(mdcId)", MdcPowerBudget.MDC_ID_MAX);
        if (r.budgetWatts() == null) throw badRequest("전력 예산(budgetWatts)은 필수입니다");
        repository.findByMdcId(mdcId).ifPresent(existing -> { throw conflict(existing); });

        MdcPowerBudget budget = new MdcPowerBudget(mdcId, budget(r.budgetWatts()));
        apply(budget, r);
        ScheduleActorResolver.Actor actor = actorResolver.current();
        budget.setCreatedBy(actor.email(), actor.name());
        budget.setUpdatedBy(actor.email(), actor.name());
        return saveOrConflict(budget);
    }

    /** 부분 수정 — 보낸 키만 반영한다. MDC 식별자를 바꾸면 중복 검사를 다시 한다. */
    @Transactional
    public MdcPowerBudget update(Long id, MdcPowerBudgetRequest r) {
        if (r == null) throw badRequest("요청 본문이 필요합니다");
        MdcPowerBudget budget = require(id);

        if (r.mdcId() != null) {
            String mdcId = requireText(r.mdcId(), "MDC 식별자(mdcId)", MdcPowerBudget.MDC_ID_MAX);
            if (!mdcId.equals(budget.getMdcId())) {
                repository.findByMdcId(mdcId).ifPresent(existing -> { throw conflict(existing); });
                budget.setMdcId(mdcId);
            }
        }
        if (r.budgetWatts() != null) budget.setBudgetWatts(budget(r.budgetWatts()));
        apply(budget, r);
        ScheduleActorResolver.Actor actor = actorResolver.current();
        budget.setUpdatedBy(actor.email(), actor.name());
        return saveOrConflict(budget);
    }

    /** 삭제. 없으면 404. 지우면 다음 배치부터 그 MDC는 총량 제약을 받지 않는다. */
    @Transactional
    public void delete(Long id) {
        repository.delete(require(id));
    }

    /**
     * 저장하며 <b>동시 등록 경합</b>을 409로 바꾼다.
     *
     * <p>중복은 먼저 조회해 막지만, 같은 MDC를 동시에 등록·수정하면 그 검사를 지나쳐 유니크 제약에 걸린다.
     * 그대로 두면 500으로 새므로 여기서 409로 바꾼다(전력 사양 원장·디스패치 멱등키와 같은 처리). 제약 위반 뒤의
     * 영속성 컨텍스트는 신뢰할 수 없어 다시 조회하지 않고, 어떤 MDC가 걸렸는지만 알린다.</p>
     */
    MdcPowerBudget saveOrConflict(MdcPowerBudget budget) {
        try {
            return repository.saveAndFlush(budget);
        } catch (DataIntegrityViolationException race) {
            throw new BusinessException(HttpStatus.CONFLICT, String.format(
                    "이미 등록된 MDC 입니다: %s (동시에 등록·수정되어 유니크 제약에 걸렸습니다)", budget.getMdcId()));
        }
    }

    // ── 반영·검증 ──────────────────────────────────────────────

    private static void apply(MdcPowerBudget budget, MdcPowerBudgetRequest r) {
        if (r.source() != null) {
            budget.setSource(EnumSupport.parse(MdcPowerBudget.BudgetSource.class, r.source(), "전력 예산 출처"));
        }
        if (r.description() != null) {
            JsonNode node = r.description();
            if (node.isNull()) {
                budget.setDescription(null);
            } else {
                if (!node.isString()) throw badRequest("설명은 문자열이어야 합니다");
                budget.setDescription(limit(node.asString(), "설명", MdcPowerBudget.DESCRIPTION_MAX));
            }
        }
    }

    /**
     * 예산 검증 — 0보다 크고 유한해야 한다. <b>크기 상한은 없다.</b>
     *
     * <p>무한대만 막는 이유: 남은 여유가 {@code ∞ − 합계 = ∞}가 되어 초과 판정이 항상 통과가 되고,
     * 그러면 예산을 등록하지 않은 것과 구분되지 않는다. 응답 직렬화도 표준을 벗어난다. 큰 유한값은
     * 그 MDC가 사실상 무제한이 될 뿐 계산이 깨지지 않고, 값 자체가 진단의 MDC 요약에 그대로 보인다.</p>
     */
    private static double budget(double v) {
        if (!Double.isFinite(v) || v <= 0) {
            throw badRequest("전력 예산(budgetWatts)은 0 보다 큰 유한한 값이어야 합니다: " + v);
        }
        return v;
    }

    private static String requireText(String value, String label, int max) {
        if (value == null || value.isBlank()) throw badRequest(label + "은(는) 필수입니다");
        return limit(value.trim(), label, max);
    }

    private static String limit(String value, String label, int max) {
        if (value.length() > max) {
            throw badRequest(String.format("%s은(는) %d자 이내여야 합니다 (현재 %d자)", label, max, value.length()));
        }
        return value;
    }

    private static BusinessException conflict(MdcPowerBudget existing) {
        return new BusinessException(HttpStatus.CONFLICT, String.format(
                "이미 등록된 MDC 입니다: %s (id %d, 예산 %.1fW). 수정하려면 PUT /mdc-power-budgets/%d 를 쓰세요",
                existing.getMdcId(), existing.getId(), existing.getBudgetWatts(), existing.getId()));
    }

    private static BusinessException badRequest(String message) {
        return new BusinessException(HttpStatus.BAD_REQUEST, message);
    }
}
