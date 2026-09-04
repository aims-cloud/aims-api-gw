package com.innogrid.aims.monitoring.service.resourceexplorer.tree;

import java.util.List;

import com.innogrid.aims.infra.entity.Asset;
import org.springframework.stereotype.Component;

/**
 * 1세부 Host↔가상자원 연동 API 확정 전까지의 기본 구현 — 항상 빈 목록을 돌려준다.
 * 물리 호스트 아래 '가상자원' 그룹은 목록이 비어 있으면 생성되지 않으므로 트리 구조에 영향이 없다.
 */
// TODO:: 1세부 가상화 플랫폼 연동 API(Host ID → VM 목록) 확정 시 실제 조회 구현으로 교체
@Component
public class NoopSebu1VirtualResourceProvider implements Sebu1VirtualResourceProvider {

    @Override
    public List<VirtualResource> findByHost(Asset host) {
        return List.of();
    }
}
