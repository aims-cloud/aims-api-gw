package com.innogrid.aims.infra.ipam.service;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import com.innogrid.aims.common.exception.BusinessException;
import com.innogrid.aims.infra.ipam.dto.IpamDtos.SubnetSummary;
import com.innogrid.aims.infra.ipam.entity.NetworkSubnet;
import com.innogrid.aims.infra.ipam.entity.NetworkZone;
import com.innogrid.aims.infra.ipam.repository.IpAddressRepository;
import com.innogrid.aims.infra.ipam.repository.NetworkSubnetRepository;
import com.innogrid.aims.infra.ipam.repository.NetworkZoneRepository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class NetworkSubnetServiceTest {

    private NetworkSubnetRepository subnetRepository;
    private NetworkZoneRepository zoneRepository;
    private IpAddressRepository ipRepository;
    private NetworkAuditService audit;
    private NetworkSubnetService service;

    @BeforeEach
    void setUp() {
        subnetRepository = mock(NetworkSubnetRepository.class);
        zoneRepository = mock(NetworkZoneRepository.class);
        ipRepository = mock(IpAddressRepository.class);
        audit = mock(NetworkAuditService.class);
        service = new NetworkSubnetService(subnetRepository, zoneRepository, ipRepository, audit);
        when(zoneRepository.findAll()).thenReturn(List.of());
    }

    private NetworkSubnet subnet(long id, String cidr) {
        NetworkSubnet s = new NetworkSubnet(10L, cidr, null, null, "admin");
        setId(s, id);
        return s;
    }

    private static void setId(Object entity, long id) {
        try {
            var f = entity.getClass().getDeclaredField("id");
            f.setAccessible(true);
            f.set(entity, id);
        } catch (Exception ignored) {}
    }

    @Test
    void create_requires_valid_zone_and_cidr() {
        when(zoneRepository.existsById(10L)).thenReturn(true);

        assertThatThrownBy(() -> service.createSubnet(null, "10.0.0.0/24", null, null, "admin", "ip"))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> service.createSubnet(10L, "bad-cidr", null, null, "admin", "ip"))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getStatus()).isEqualTo(HttpStatus.BAD_REQUEST));
    }

    @Test
    void create_subnet_ok() {
        when(zoneRepository.existsById(10L)).thenReturn(true);
        when(subnetRepository.save(any(NetworkSubnet.class))).thenAnswer(inv -> {
            NetworkSubnet s = inv.getArgument(0);
            setId(s, 1L);
            return s;
        });
        when(ipRepository.countBySubnetId(1L)).thenReturn(0L);

        SubnetSummary summary = service.createSubnet(10L, "192.168.110.0/24", "192.168.110.1", "설명", "admin", "ip");

        assertThat(summary.cidr()).isEqualTo("192.168.110.0/24");
        assertThat(summary.totalHosts()).isEqualTo(254);
    }

    @Test
    void summary_computes_ip_usage_percent() {
        when(subnetRepository.findAll()).thenReturn(List.of(subnet(1L, "192.168.110.0/24")));
        when(ipRepository.countBySubnetId(1L)).thenReturn(33L);
        when(zoneRepository.findAll()).thenReturn(List.of(zone()));

        List<SubnetSummary> list = service.listSubnets(null);

        assertThat(list.get(0).usedCount()).isEqualTo(33);
        assertThat(list.get(0).usagePercent()).isEqualTo(12.99); // 33/254 = 12.99%
    }

    private NetworkZone zone() {
        NetworkZone z = new NetworkZone("Openstack-100", null, "admin");
        setId(z, 10L);
        return z;
    }

    @Test
    void get_subnet_not_found() {
        when(subnetRepository.findById(99L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.getSubnet(99L))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getStatus()).isEqualTo(HttpStatus.NOT_FOUND));
    }
}
