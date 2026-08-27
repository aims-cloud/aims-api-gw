package com.innogrid.aims.resource.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;

/**
 * 가상서버 그룹 멤버.
 * VM은 CB-Spider 자원이므로 (연결명, VM명) 쌍으로 식별한다.
 */
@Embeddable
public class VmGroupMember {

    @Column(nullable = false, length = 100)
    private String connectionName;

    @Column(nullable = false, length = 100)
    private String vmName;

    protected VmGroupMember() {}

    public VmGroupMember(String connectionName, String vmName) {
        this.connectionName = connectionName;
        this.vmName = vmName;
    }

    public String getConnectionName() { return connectionName; }
    public String getVmName() { return vmName; }
}
