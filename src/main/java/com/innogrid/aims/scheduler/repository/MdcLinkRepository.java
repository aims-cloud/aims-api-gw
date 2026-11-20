package com.innogrid.aims.scheduler.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.innogrid.aims.scheduler.entity.MdcLink;

/**
 * MDC 링크 원장 조회. 쌍은 항상 정렬되어 저장되므로 조회할 때도 {@link MdcLink.Pair#of}로 정렬해 넘긴다.
 * 목록은 매트릭스가 결정적으로 나오도록 (mdcA, mdcB) 순으로 고정한다.
 */
public interface MdcLinkRepository extends JpaRepository<MdcLink, Long> {

    /** 정렬된 쌍으로 조회한다. 호출자는 {@code Pair.of(a, b)}를 거쳐야 한다. */
    Optional<MdcLink> findByMdcAAndMdcB(String mdcA, String mdcB);

    List<MdcLink> findAllByOrderByMdcAAscMdcBAsc();

    /** 어느 편이든 주어진 MDC가 걸린 링크. 두 인자에 같은 값을 넘긴다. */
    List<MdcLink> findByMdcAOrMdcBOrderByMdcAAscMdcBAsc(String mdcA, String mdcB);
}
