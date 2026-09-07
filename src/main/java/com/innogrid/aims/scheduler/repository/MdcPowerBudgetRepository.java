package com.innogrid.aims.scheduler.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.innogrid.aims.scheduler.entity.MdcPowerBudget;

/** MDC 전력 예산 원장 조회. 목록은 MDC 식별자 순으로 고정해 화면·응답이 결정적이게 한다. */
public interface MdcPowerBudgetRepository extends JpaRepository<MdcPowerBudget, Long> {

    Optional<MdcPowerBudget> findByMdcId(String mdcId);

    List<MdcPowerBudget> findAllByOrderByMdcIdAsc();
}
