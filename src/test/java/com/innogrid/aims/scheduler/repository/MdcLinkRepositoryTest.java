package com.innogrid.aims.scheduler.repository;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.annotation.Transactional;

import com.innogrid.aims.scheduler.entity.MdcLink;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 원장 저장 규칙 — 정렬된 쌍 조회, 유니크 제약, 어느 편이든 걸린 링크 조회.
 */
@SpringBootTest
@Transactional
class MdcLinkRepositoryTest {

    @Autowired
    MdcLinkRepository repository;

    @BeforeEach
    void clean() {
        repository.deleteAll();
    }

    @DisplayName("역방향으로 저장한 쌍도 정렬된 쌍으로 찾는다")
    @Test
    void reverse_pair_found_by_sorted_pair() {
        repository.save(new MdcLink("mdc-02", "mdc-01", 10));

        MdcLink.Pair p = MdcLink.Pair.of("mdc-02", "mdc-01");
        assertTrue(repository.findByMdcAAndMdcB(p.a(), p.b()).isPresent());
        assertTrue(repository.findByMdcAAndMdcB("mdc-02", "mdc-01").isEmpty(),
                "정렬하지 않은 순서로는 찾지 못한다 — 호출자는 Pair.of 를 거쳐야 한다");
    }

    @DisplayName("같은 쌍을 두 번 저장하면 유니크 제약에 걸린다")
    @Test
    void duplicate_pair_violates_unique() {
        repository.saveAndFlush(new MdcLink("a", "b", 1));

        assertThrows(DataIntegrityViolationException.class,
                () -> repository.saveAndFlush(new MdcLink("b", "a", 2)));
    }

    @DisplayName("어느 편이든 걸린 링크를 정렬된 순서로 돌려준다")
    @Test
    void links_on_either_side_sorted() {
        repository.save(new MdcLink("mdc-01", "mdc-03", 1));
        repository.save(new MdcLink("mdc-02", "mdc-03", 1));
        repository.save(new MdcLink("mdc-01", "mdc-02", 1));

        List<MdcLink> withThree = repository.findByMdcAOrMdcBOrderByMdcAAscMdcBAsc("mdc-03", "mdc-03");
        assertEquals(2, withThree.size());
        assertEquals("mdc-01", withThree.get(0).getMdcA());
        assertEquals("mdc-02", withThree.get(1).getMdcA());

        List<MdcLink> all = repository.findAllByOrderByMdcAAscMdcBAsc();
        assertEquals(List.of("mdc-02", "mdc-03", "mdc-03"), all.stream().map(MdcLink::getMdcB).toList());
    }
}
