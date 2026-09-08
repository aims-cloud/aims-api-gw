package com.innogrid.aims.infra.ipam.service;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;

import com.innogrid.aims.common.exception.BusinessException;
import com.innogrid.aims.infra.ipam.dto.IpamDtos.InterfaceRef;
import com.innogrid.aims.infra.ipam.dto.IpamDtos.ZoneSummary;
import com.innogrid.aims.infra.ipam.entity.NetworkZone;
import com.innogrid.aims.infra.ipam.entity.NetworkZoneInterface;
import com.innogrid.aims.infra.ipam.repository.IpAddressRepository;
import com.innogrid.aims.infra.ipam.repository.NetworkSubnetRepository;
import com.innogrid.aims.infra.ipam.repository.NetworkZoneInterfaceRepository;
import com.innogrid.aims.infra.ipam.repository.NetworkZoneRepository;
import com.innogrid.aims.openstack.OpenStackNetworkCatalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class NetworkZoneServiceTest {

    private NetworkZoneRepository zoneRepository;
    private NetworkZoneInterfaceRepository interfaceRepository;
    private NetworkSubnetRepository subnetRepository;
    private IpAddressRepository ipRepository;
    private OpenStackNetworkCatalog catalog;
    private NetworkAuditService audit;
    private NetworkZoneService service;

    @BeforeEach
    void setUp() {
        zoneRepository = mock(NetworkZoneRepository.class);
        interfaceRepository = mock(NetworkZoneInterfaceRepository.class);
        subnetRepository = mock(NetworkSubnetRepository.class);
        ipRepository = mock(IpAddressRepository.class);
        catalog = mock(OpenStackNetworkCatalog.class);
        audit = mock(NetworkAuditService.class);
        service = new NetworkZoneService(zoneRepository, interfaceRepository, subnetRepository,
                ipRepository, catalog, audit);
        when(subnetRepository.countByZoneId(any())).thenReturn(0L);
        when(interfaceRepository.findByZoneId(any())).thenReturn(List.of());
    }

    @Test
    void create_zone_records_history() {
        when(zoneRepository.existsByName("prod-db")).thenReturn(false);
        when(zoneRepository.save(any(NetworkZone.class))).thenAnswer(inv -> inv.getArgument(0));

        ZoneSummary summary = service.createZone("prod-db", "운영 DB 격리 구역", "admin", "192.168.0.1");

        assertThat(summary.name()).isEqualTo("prod-db");
        verify(audit).record(any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void create_zone_rejects_duplicate_name() {
        when(zoneRepository.existsByName("dup")).thenReturn(true);
        assertThatThrownBy(() -> service.createZone("dup", null, "admin", "ip"))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getStatus()).isEqualTo(HttpStatus.CONFLICT));
    }

    @Test
    void save_interfaces_replaces_all() {
        when(zoneRepository.findById(1L)).thenReturn(Optional.of(new NetworkZone("z", null, "admin")));
        when(interfaceRepository.findByZoneId(1L)).thenReturn(List.of());

        service.saveInterfaces(1L, List.of(
                new InterfaceRef("rnd openstack", "internal", "net-1", null)), "admin", "ip");

        verify(interfaceRepository).deleteByZoneId(1L);
        ArgumentCaptor<NetworkZoneInterface> cap = ArgumentCaptor.forClass(NetworkZoneInterface.class);
        verify(interfaceRepository).save(cap.capture());
        assertThat(cap.getValue().getNetworkName()).isEqualTo("internal");
    }

    @Test
    void delete_zone_cascades() {
        when(zoneRepository.findById(1L)).thenReturn(Optional.of(new NetworkZone("z", null, "admin")));
        when(subnetRepository.findByZoneId(1L)).thenReturn(List.of());

        service.deleteZone(1L, "admin", "ip");

        verify(ipRepository).deleteByZoneId(1L);
        verify(interfaceRepository).deleteByZoneId(1L);
        verify(zoneRepository).delete(any(NetworkZone.class));
    }

    @Test
    void get_zone_not_found() {
        when(zoneRepository.findById(99L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.getZone(99L))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getStatus()).isEqualTo(HttpStatus.NOT_FOUND));
    }
}
