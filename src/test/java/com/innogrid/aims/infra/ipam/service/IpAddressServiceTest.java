package com.innogrid.aims.infra.ipam.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import com.innogrid.aims.common.exception.BusinessException;
import com.innogrid.aims.infra.ipam.dto.IpamDtos.IpRow;
import com.innogrid.aims.infra.ipam.entity.IpAddress;
import com.innogrid.aims.infra.ipam.entity.NetworkSubnet;
import com.innogrid.aims.infra.ipam.repository.IpAddressRepository;
import com.innogrid.aims.infra.ipam.repository.NetworkSubnetRepository;
import com.innogrid.aims.infra.ipam.repository.NetworkZoneRepository;
import com.innogrid.aims.openstack.OpenStackNetworkCatalog;
import com.innogrid.aims.openstack.OpenStackNetworkCatalog.AllocatedIp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class IpAddressServiceTest {

    private IpAddressRepository ipRepository;
    private NetworkSubnetRepository subnetRepository;
    private NetworkZoneRepository zoneRepository;
    private OpenStackNetworkCatalog catalog;
    private NetworkAuditService audit;
    private IpAddressService service;

    @BeforeEach
    void setUp() {
        ipRepository = mock(IpAddressRepository.class);
        subnetRepository = mock(NetworkSubnetRepository.class);
        zoneRepository = mock(NetworkZoneRepository.class);
        catalog = mock(OpenStackNetworkCatalog.class);
        audit = mock(NetworkAuditService.class);
        service = new IpAddressService(ipRepository, subnetRepository, zoneRepository, catalog, audit);

        when(zoneRepository.findAll()).thenReturn(List.of());
        when(subnetRepository.findAll()).thenReturn(List.of());
        when(ipRepository.save(any(IpAddress.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    private NetworkSubnet subnet() {
        NetworkSubnet s = new NetworkSubnet(10L, "192.168.110.0/24", null, null, "admin");
        try {
            var f = NetworkSubnet.class.getDeclaredField("id");
            f.setAccessible(true);
            f.set(s, 1L);
        } catch (Exception ignored) {}
        return s;
    }

    @Test
    void reserve_single_ip_within_subnet() {
        when(subnetRepository.findById(1L)).thenReturn(Optional.of(subnet()));
        when(ipRepository.findBySubnetIdAndIpAddress(1L, "192.168.110.50")).thenReturn(Optional.empty());

        int created = service.reserve(1L, "192.168.110.50", null, "예약", "admin", "ip");

        assertThat(created).isEqualTo(1);
        verify(ipRepository).save(any(IpAddress.class));
    }

    @Test
    void reserve_range_creates_multiple() {
        when(subnetRepository.findById(1L)).thenReturn(Optional.of(subnet()));
        when(ipRepository.findBySubnetIdAndIpAddress(any(), any())).thenReturn(Optional.empty());

        int created = service.reserve(1L, "192.168.110.10", "192.168.110.12", "범위예약", "admin", "ip");

        assertThat(created).isEqualTo(3); // .10, .11, .12
    }

    @Test
    void reserve_rejects_ip_outside_subnet() {
        when(subnetRepository.findById(1L)).thenReturn(Optional.of(subnet()));

        assertThatThrownBy(() -> service.reserve(1L, "10.0.0.5", null, null, "admin", "ip"))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getStatus()).isEqualTo(HttpStatus.BAD_REQUEST));
    }

    @Test
    void sync_marks_openstack_ip_as_allocated_and_preserves_existing() {
        NetworkSubnet s = subnet();
        when(subnetRepository.findById(1L)).thenReturn(Optional.of(s));

        // 이미 예약돼 있던 IP가 OpenStack에서 실제 사용됨 → 할당으로 전환
        IpAddress reserved = new IpAddress(10L, 1L, "192.168.110.47", "미리 예약", "admin");
        reserved.setAlias("웹서버");
        List<IpAddress> existing = new ArrayList<>(List.of(reserved));
        when(ipRepository.findBySubnetId(1L)).thenReturn(existing);
        when(catalog.listAllocatedIps()).thenReturn(List.of(
                new AllocatedIp("192.168.110.47", "fa:16:3e:aa:bb:cc", "INSTANCE", "srv-1", "web-01", "external")));

        List<IpRow> rows = service.listBySubnet(1L);

        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).status()).isEqualTo("ALLOCATED");
        assertThat(rows.get(0).resourceName()).isEqualTo("web-01");
        assertThat(rows.get(0).macAddress()).isEqualTo("fa:16:3e:aa:bb:cc");
        assertThat(rows.get(0).alias()).isEqualTo("웹서버"); // 사용자 오버레이 보존
    }

    @Test
    void sync_ignores_ips_outside_subnet_cidr() {
        NetworkSubnet s = subnet();
        when(subnetRepository.findById(1L)).thenReturn(Optional.of(s));
        when(ipRepository.findBySubnetId(1L)).thenReturn(new ArrayList<>());
        when(catalog.listAllocatedIps()).thenReturn(List.of(
                new AllocatedIp("10.0.0.5", "mac", "INSTANCE", "srv-x", "other", "internal"))); // 다른 서브넷

        service.listBySubnet(1L);

        // CIDR 밖이라 저장 안 됨
        verify(ipRepository, org.mockito.Mockito.never()).save(any(IpAddress.class));
    }

    @Test
    void update_description_only() {
        IpAddress ip = new IpAddress(10L, 1L, "192.168.110.50", null, "admin");
        when(ipRepository.findById(5L)).thenReturn(Optional.of(ip));

        IpRow row = service.update(5L, "운영 DB", "admin", "ip");

        assertThat(row.description()).isEqualTo("운영 DB");
    }
}
