package com.innogrid.aims.auth.repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import com.innogrid.aims.auth.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface UserRepository extends JpaRepository<User, Long> {

    Optional<User> findByEmail(String email);

    boolean existsByEmail(String email);

    /** 자원 탐색기 역할 테이블 — 역할에 속한 멤버(사용자) 수 */
    long countByRoles_Name(String roleName);

    /** 알람 정책 생성자(이메일) → 표시명 조회 */
    List<User> findByEmailIn(Collection<String> emails);

    /** 알람 전송 — 조직 수신자 전개(users.organization = 조직명) */
    List<User> findByOrganization(String organization);

    /** 알람 전송 — 무소속("미지정") 조직 수신자 전개 */
    @Query("select u from User u where u.organization is null or length(trim(u.organization)) = 0")
    List<User> findUnassigned();
}
