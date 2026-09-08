package com.innogrid.aims.infra.ipam.service;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Service;

import com.innogrid.aims.infra.ipam.dto.IpamDtos.HistoryRow;
import com.innogrid.aims.infra.ipam.entity.NetworkAuditHistory;
import com.innogrid.aims.infra.ipam.entity.NetworkAuditHistory.Action;
import com.innogrid.aims.infra.ipam.entity.NetworkAuditHistory.Result;
import com.innogrid.aims.infra.ipam.entity.NetworkAuditHistory.Target;
import com.innogrid.aims.infra.ipam.repository.NetworkAuditHistoryRepository;

/**
 * 네트워크 IPAM 공용 행위이력 기록·조회.
 */
@Service
public class NetworkAuditService {

    private final NetworkAuditHistoryRepository repository;

    public NetworkAuditService(NetworkAuditHistoryRepository repository) {
        this.repository = repository;
    }

    public void record(Target target, Long targetId, Action action, Result result,
                       String detail, String performedBy, String clientIp, OffsetDateTime startedAt) {
        repository.save(new NetworkAuditHistory(target, targetId, action, result,
                detail, performedBy, clientIp, startedAt));
    }

    public List<HistoryRow> list(Target target, Long targetId) {
        List<HistoryRow> rows = new ArrayList<>();
        for (NetworkAuditHistory h : repository.findByTargetAndTargetIdOrderByStartedAtDesc(target, targetId)) {
            rows.add(new HistoryRow(h.getId(), h.getPerformedBy(), h.getClientIp(),
                    h.getTarget().name(), h.getAction().name(), h.getResult().name(),
                    h.getDetail(), h.getStartedAt(), h.getCompletedAt()));
        }
        return rows;
    }
}
