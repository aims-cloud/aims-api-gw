package com.innogrid.aims.infra.service;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import com.innogrid.aims.common.exception.BusinessException;
import com.innogrid.aims.infra.entity.Rack;
import com.innogrid.aims.infra.entity.RackUnit;
import com.innogrid.aims.infra.repository.RackRepository;
import com.innogrid.aims.infra.repository.RackUnitRepository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RackServiceTest {

    private RackRepository rackRepository;
    private RackUnitRepository rackUnitRepository;
    private RackService service;

    @BeforeEach
    void setUp() {
        rackRepository = mock(RackRepository.class);
        rackUnitRepository = mock(RackUnitRepository.class);
        service = new RackService(rackRepository, rackUnitRepository);
    }

    @Test
    void layout_builds_full_unit_grid_with_occupancy() {
        Rack rack = new Rack("mdc-1", "RACK-A01", 5, 10.0);
        when(rackRepository.findById(1L)).thenReturn(Optional.of(rack));
        when(rackUnitRepository.findByRackId(1L)).thenReturn(List.of(
                new RackUnit(rack, "U2", "gpu-node-01", "GPU_SERVER"),
                new RackUnit(rack, "4", "switch-01", "SWITCH")));

        Map<String, Object> layout = service.getLayout(1L);

        assertThat(layout)
                .containsEntry("totalUnits", 5)
                .containsEntry("usedUnits", 2)
                .containsEntry("freeUnits", 3);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> units = (List<Map<String, Object>>) layout.get("units");
        assertThat(units).hasSize(5);
        assertThat(units.get(1))   // U2
                .containsEntry("occupied", true)
                .containsEntry("assetName", "gpu-node-01");
        assertThat(units.get(3))   // U4 — "4" 표기도 인식
                .containsEntry("occupied", true)
                .containsEntry("assetName", "switch-01");
        assertThat(units.get(0)).containsEntry("occupied", false);
    }

    @Test
    void layout_includes_placement_for_3d_view() {
        Rack rack = new Rack("mdc-1", "RACK-A01", 42, 10.0);
        rack.setCategory("서버랙");
        rack.setLocation("본관 전산실");
        rack.setFloorNo("3F");
        rack.setZoneName("A구역");
        rack.setRowNo("1열");
        rack.setPositionNo("3");
        when(rackRepository.findById(1L)).thenReturn(Optional.of(rack));
        when(rackUnitRepository.findByRackId(1L)).thenReturn(List.of());

        Map<String, Object> layout = service.getLayout(1L);

        @SuppressWarnings("unchecked")
        Map<String, Object> placement = (Map<String, Object>) layout.get("placement");
        assertThat(placement)
                .containsEntry("category", "서버랙")
                .containsEntry("location", "본관 전산실")
                .containsEntry("floorNo", "3F")
                .containsEntry("zoneName", "A구역")
                .containsEntry("rowNo", "1열")
                .containsEntry("positionNo", "3");
    }

    @Test
    void layout_throws_not_found_for_missing_rack() {
        when(rackRepository.findById(anyLong())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getLayout(99L))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getStatus())
                        .isEqualTo(HttpStatus.NOT_FOUND));
    }

    @Test
    void unit_position_parsing_handles_various_formats() {
        assertThat(RackService.parseUnitPosition("U10")).isEqualTo(10);
        assertThat(RackService.parseUnitPosition("u7")).isEqualTo(7);
        assertThat(RackService.parseUnitPosition("15")).isEqualTo(15);
        assertThat(RackService.parseUnitPosition(" U3 ")).isEqualTo(3);
        assertThat(RackService.parseUnitPosition("0")).isNull();
        assertThat(RackService.parseUnitPosition("top")).isNull();
        assertThat(RackService.parseUnitPosition(null)).isNull();
        assertThat(RackService.parseUnitPosition("")).isNull();
    }
}
