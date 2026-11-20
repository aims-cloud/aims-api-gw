package com.innogrid.aims.monitoring.log;

import com.innogrid.aims.monitoring.dto.log.PortalLogResult;
import com.innogrid.aims.monitoring.dto.log.PortalLogType;
import com.innogrid.aims.monitoring.entity.PortalLogEntry;
import com.innogrid.aims.monitoring.repository.PortalLogRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 포털 로그 저장기 — 본 업무 트랜잭션이 롤백돼도 로그는 남고(REQUIRES_NEW), 진행중 행 완료 갱신이 동작한다.
 * 클래스에 @Transactional 을 붙이지 않는다 — 별도 트랜잭션 커밋을 실제로 확인해야 하므로 뒤처리는 @AfterEach 로 지운다.
 */
@SpringBootTest
class PortalLogWriterTest {

    @Autowired
    PortalLogWriter writer;
    @Autowired
    PortalLogRepository repository;
    @Autowired
    PlatformTransactionManager transactionManager;

    @AfterEach
    void tearDown() {
        repository.deleteAll();
    }

    @Test
    void 바깥_트랜잭션이_롤백돼도_로그는_남는다() {
        TransactionTemplate tx = new TransactionTemplate(transactionManager);

        tx.executeWithoutResult(status -> {
            writer.write(PortalLogEntry.builder(PortalLogType.AUDIT)
                    .screen("네트워크 구역").action("생성").content("네트워크 구역 생성: zone-a")
                    .result(PortalLogResult.FAILURE).description("제약 위반").build());
            status.setRollbackOnly();                                   // 본 업무 실패를 흉내
        });

        assertThat(repository.count()).isEqualTo(1);
        assertThat(repository.findAll().get(0).getResult()).isEqualTo(PortalLogResult.FAILURE);
    }

    @Test
    void 진행중_행을_ref로_찾아_완료하고_대상이_없으면_false다() {
        writer.write(PortalLogEntry.builder(PortalLogType.ACTION)
                .screen("가상머신").action("생성").content("Infra 배포: infra-1")
                .result(PortalLogResult.IN_PROGRESS).ref("INFRA_JOB", "job-1").build());

        assertThat(writer.complete("INFRA_JOB", "job-1", PortalLogResult.SUCCESS, null)).isTrue();
        PortalLogEntry done = repository.findAll().get(0);
        assertThat(done.getResult()).isEqualTo(PortalLogResult.SUCCESS);
        assertThat(done.getCompletedAt()).isNotNull();

        assertThat(writer.complete("INFRA_JOB", "job-1", PortalLogResult.SUCCESS, null)).isFalse();   // 이미 완료
        assertThat(writer.complete("INFRA_JOB", "job-x", PortalLogResult.FAILURE, "없음")).isFalse();
    }
}
