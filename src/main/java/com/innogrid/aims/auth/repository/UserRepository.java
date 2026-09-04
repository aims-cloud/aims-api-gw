package com.innogrid.aims.auth.repository;

import java.util.Optional;

import com.innogrid.aims.auth.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserRepository extends JpaRepository<User, Long> {

    Optional<User> findByEmail(String email);

    boolean existsByEmail(String email);

    /** 자원 탐색기 역할 테이블 — 역할에 속한 멤버(사용자) 수 */
    long countByRoles_Name(String roleName);
}
