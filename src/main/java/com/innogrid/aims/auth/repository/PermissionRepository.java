package com.innogrid.aims.auth.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import com.innogrid.aims.auth.entity.Permission;

public interface PermissionRepository extends JpaRepository<Permission, Long> {
}
