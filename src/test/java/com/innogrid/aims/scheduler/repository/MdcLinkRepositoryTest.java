package com.innogrid.aims.scheduler.repository;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
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

    @Test
    void 역방향으로_저장한_쌍도_정렬된_쌍으로_찾는다() {
        repository.save(new MdcLink("mdc-02", "mdc-01", 10));

        MdcLink.Pair p = MdcLink.Pair.of("mdc-02", "mdc-01");
        assertTrue(repository.findByMdcAAndMdcB(p.a(), p.b()).isPresent());
        assertTrue(repository.findByMdcAAndMdcB("mdc-02", "mdc-01").isEmpty(),
                "정렬하지 않은 순서로는 찾지 못한다 — 호출자는 Pair.of 를 거쳐야 한다");
    }

    @Test
    void 같은_쌍을_두_번_저장하면_유니크_제약에_걸린다() {
        repository.saveAndFlush(new MdcLink("a", "b", 1));

        assertThrows(DataIntegrityViolationException.class,
                () -> repository.saveAndFlush(new MdcLink("b", "a", 2)));
    }

    @Test
    void 어느_편이든_걸린_링크를_정렬된_순서로_돌려준다() {
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
