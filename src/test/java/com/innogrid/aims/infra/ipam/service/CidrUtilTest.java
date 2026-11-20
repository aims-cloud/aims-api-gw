package com.innogrid.aims.infra.ipam.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CidrUtilTest {

    @Test
    void range_of_24() {
        CidrUtil.Range r = CidrUtil.range("192.168.110.0/24");
        assertThat(r.first()).isEqualTo("192.168.110.1");
        assertThat(r.last()).isEqualTo("192.168.110.254");
        assertThat(r.totalHosts()).isEqualTo(254);
    }

    @Test
    void range_of_16() {
        CidrUtil.Range r = CidrUtil.range("172.16.0.0/16");
        assertThat(r.totalHosts()).isEqualTo(65534);
        assertThat(r.first()).isEqualTo("172.16.0.1");
        assertThat(r.last()).isEqualTo("172.16.255.254");
    }

    @Test
    void range_of_28() {
        CidrUtil.Range r = CidrUtil.range("10.254.0.16/28");
        assertThat(r.totalHosts()).isEqualTo(14);
        assertThat(r.first()).isEqualTo("10.254.0.17");
        assertThat(r.last()).isEqualTo("10.254.0.30");
    }

    @Test
    void contains_checks_membership() {
        assertThat(CidrUtil.contains("192.168.110.0/24", "192.168.110.47")).isTrue();
        assertThat(CidrUtil.contains("192.168.110.0/24", "192.168.111.1")).isFalse();
        assertThat(CidrUtil.contains("10.0.0.0/24", "10.0.0.234")).isTrue();
    }

    @Test
    void validates_cidr() {
        assertThat(CidrUtil.isValidCidr("10.0.0.0/24")).isTrue();
        assertThat(CidrUtil.isValidCidr("10.0.0.0/33")).isFalse();
        assertThat(CidrUtil.isValidCidr("nonsense")).isFalse();
        assertThat(CidrUtil.isValidCidr("10.0.0.0")).isFalse();
    }
}
