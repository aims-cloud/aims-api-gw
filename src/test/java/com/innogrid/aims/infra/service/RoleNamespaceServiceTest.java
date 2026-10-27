package com.innogrid.aims.infra.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.innogrid.aims.auth.dto.AuthDto.NamespaceCandidateResponse;
import com.innogrid.aims.auth.dto.AuthDto.RoleNamespaceResponse;
import com.innogrid.aims.auth.entity.Role;
import com.innogrid.aims.auth.repository.RoleRepository;
import com.innogrid.aims.cloudbarista.tumblebug.TumblebugClient;
import com.innogrid.aims.common.exception.BusinessException;
import com.innogrid.aims.infra.entity.RoleNamespace;
import com.innogrid.aims.infra.repository.RoleNamespaceRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.ResourceAccessException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 역할 ↔ Namespace 배정 — Tumblebug 존재 검증, 중복 건너뛰기, 표시 이름 보강, 해제 404, 역할 삭제 시 배정 정리. */
class RoleNamespaceServiceTest {

    RoleRepository roleRepository = mock(RoleRepository.class);
    RoleNamespaceRepository mappings = mock(RoleNamespaceRepository.class);
    TumblebugClient tumblebug = mock(TumblebugClient.class);
    RoleNamespaceService service = new RoleNamespaceService(roleRepository, mappings, tumblebug);

    Role role = new Role("OPERATOR", "운영자");
    List<RoleNamespace> stored = new ArrayList<>();

    @BeforeEach
    void setUp() {
        when(roleRepository.findById(1L)).thenReturn(Optional.of(role));
        when(roleRepository.findById(9L)).thenReturn(Optional.empty());
        when(mappings.findByRole_Id(1L)).thenAnswer(inv -> List.copyOf(stored));
        when(mappings.save(any(RoleNamespace.class))).thenAnswer(inv -> {
            RoleNamespace saved = inv.getArgument(0);
            stored.add(saved);
            return saved;
        });
        when(tumblebug.listNamespaces()).thenReturn(Map.of("ns", List.of(
                Map.of("id", "aws", "name", "AWS 테스트", "description", "GPU"),
                Map.of("id", "default", "name", ""),
                Map.of("id", "", "name", "id 없음"))));
    }

    @Test
    void 배정_목록은_nsId_순이고_Tumblebug_표시_이름을_붙인다() {
        stored.add(new RoleNamespace(role, "gone"));
        stored.add(new RoleNamespace(role, "aws"));

        List<RoleNamespaceResponse> rows = service.assigned(1L);

        assertThat(rows).extracting(RoleNamespaceResponse::nsId).containsExactly("aws", "gone");
        assertThat(rows).extracting(RoleNamespaceResponse::name).containsExactly("AWS 테스트", null);   // Tumblebug 에서 사라진 ns
    }

    @Test
    void Tumblebug_장애여도_배정_목록은_DB로_답하고_이름만_비운다() {
        stored.add(new RoleNamespace(role, "aws"));
        when(tumblebug.listNamespaces()).thenThrow(new ResourceAccessException("Connection refused"));

        assertThat(service.assigned(1L)).singleElement().satisfies(r -> {
            assertThat(r.nsId()).isEqualTo("aws");
            assertThat(r.name()).isNull();
        });
    }

    @Test
    void 후보는_Tumblebug_전체와_배정_여부이고_이름이_비면_id를_쓴다() {
        stored.add(new RoleNamespace(role, "aws"));

        List<NamespaceCandidateResponse> rows = service.candidates(1L);

        assertThat(rows).extracting(NamespaceCandidateResponse::nsId).containsExactly("aws", "default");   // id 없는 행 제외
        assertThat(rows).extracting(NamespaceCandidateResponse::assigned).containsExactly(true, false);
        assertThat(rows.get(1).name()).isEqualTo("default");
        assertThat(rows.get(0).description()).isEqualTo("GPU");
    }

    @Test
    void 배정은_공백을_다듬고_이미_배정된_것은_건너뛴다() {
        stored.add(new RoleNamespace(role, "aws"));

        List<RoleNamespaceResponse> rows = service.assign(1L, List.of(" default ", "aws", "default"));

        assertThat(rows).extracting(RoleNamespaceResponse::nsId).containsExactly("aws", "default");
        verify(mappings).save(any(RoleNamespace.class));                       // default 1건만 새로
    }

    @Test
    void Tumblebug에_없는_nsId가_하나라도_있으면_아무것도_넣지_않고_400() {
        assertThatThrownBy(() -> service.assign(1L, List.of("aws", "typo", "nope")))
                .isInstanceOfSatisfying(BusinessException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(e.getMessage()).contains("typo, nope");
                });
        verify(mappings, never()).save(any());
    }

    @Test
    void 빈_요청은_Tumblebug을_부르지_않고_400() {
        assertThatThrownBy(() -> service.assign(1L, List.of(" ", "")))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST));
        assertThatThrownBy(() -> service.assign(1L, null)).isInstanceOf(BusinessException.class);
        verify(tumblebug, never()).listNamespaces();
    }

    @Test
    void Tumblebug_연결_실패는_배정을_막고_예외를_그대로_올린다() {
        when(tumblebug.listNamespaces()).thenThrow(new ResourceAccessException("Connection refused"));

        assertThatThrownBy(() -> service.assign(1L, List.of("aws"))).isInstanceOf(ResourceAccessException.class);  // 공통 처리기 503
        assertThatThrownBy(() -> service.candidates(1L)).isInstanceOf(ResourceAccessException.class);
        verify(mappings, never()).save(any());
    }

    @Test
    void 해제는_배정이_없으면_404() {
        RoleNamespace mapping = new RoleNamespace(role, "aws");
        when(mappings.findByRole_IdAndNsId(1L, "aws")).thenReturn(Optional.of(mapping));
        when(mappings.findByRole_IdAndNsId(1L, "default")).thenReturn(Optional.empty());

        service.unassign(1L, "aws");
        verify(mappings).delete(mapping);

        assertThatThrownBy(() -> service.unassign(1L, "default"))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.NOT_FOUND));
    }

    @Test
    void 없는_역할은_모두_404() {
        assertThatThrownBy(() -> service.assigned(9L)).isInstanceOf(BusinessException.class).hasMessage("역할을 찾을 수 없습니다");
        assertThatThrownBy(() -> service.candidates(9L)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> service.assign(9L, List.of("aws"))).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> service.unassign(9L, "aws")).isInstanceOf(BusinessException.class);
    }

    @Test
    void 역할_삭제는_Namespace_배정을_먼저_지운다() {
        stored.add(new RoleNamespace(role, "aws"));

        service.deleteRole(1L);

        InOrder order = inOrder(mappings, roleRepository);
        order.verify(mappings).deleteAll(List.copyOf(stored));
        order.verify(roleRepository).deleteById(1L);
    }
}
