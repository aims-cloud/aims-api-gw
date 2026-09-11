package com.innogrid.aims.monitoring.log;

import java.time.OffsetDateTime;

import com.innogrid.aims.monitoring.dto.log.PortalLogResult;
import com.innogrid.aims.monitoring.entity.PortalLogEntry;
import com.innogrid.aims.monitoring.repository.PortalLogRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 포털 로그 저장기 (docs/08 §5.3).
 *
 * 항상 {@code REQUIRES_NEW} 로 별도 트랜잭션에 저장한다 — 본 업무가 롤백돼도 FAILURE 행은 남고,
 * 로그 저장 실패는 본 요청에 영향을 주지 않는다(warn 만 남김).
 * 저장 실패를 삼키는 책임은 호출자가 아니라 여기에 있다.
 */
@Component
public class PortalLogWriter {

    private static final Logger log = LoggerFactory.getLogger(PortalLogWriter.class);

    private final PortalLogRepository repository;

    public PortalLogWriter(PortalLogRepository repository) {
        this.repository = repository;
    }

    /** 새 행 저장. 실패하면 null 을 돌려주고 warn 만 남긴다 */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public PortalLogEntry write(PortalLogEntry entry) {
        try {
            return repository.save(entry);
        } catch (RuntimeException e) {
            log.warn("포털 로그 저장 실패 (type={}, content={}): {}", entry.getType(), entry.getContent(), e.getMessage());
            return null;
        }
    }

    /** 진행중(IN_PROGRESS) 행을 최종 결과로 갱신. 대상이 없으면 false */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean complete(String refType, String refId, PortalLogResult result, String description) {
        try {
            return repository.findFirstByRefTypeAndRefIdAndResultOrderByStartedAtDesc(refType, refId, PortalLogResult.IN_PROGRESS)
                    .map(entry -> {
                        entry.complete(result, description, OffsetDateTime.now());
                        return true;
                    })
                    .orElse(false);
        } catch (RuntimeException e) {
            log.warn("포털 로그 완료 갱신 실패 (ref={}/{}): {}", refType, refId, e.getMessage());
            return false;
        }
    }
}
