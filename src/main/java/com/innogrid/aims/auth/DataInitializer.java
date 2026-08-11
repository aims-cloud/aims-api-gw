package com.innogrid.aims.auth;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import com.innogrid.aims.auth.entity.Role;
import com.innogrid.aims.auth.entity.User;
import com.innogrid.aims.auth.repository.RoleRepository;
import com.innogrid.aims.auth.repository.UserRepository;

/**
 * 앱 시작 시 기본 역할과 관리자 계정을 생성한다.
 * 관리자 계정 정보는 환경변수로 주입받는다.
 */
@Component
public class DataInitializer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DataInitializer.class);

    private final RoleRepository roleRepository;
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    @Value("${aims.admin.email}")
    private String adminEmail;

    @Value("${aims.admin.password}")
    private String adminPassword;

    @Value("${aims.admin.name:관리자}")
    private String adminName;

    public DataInitializer(RoleRepository roleRepository, UserRepository userRepository,
                           PasswordEncoder passwordEncoder) {
        this.roleRepository = roleRepository;
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
    }

    @Override
    public void run(ApplicationArguments args) {
        Role adminRole = roleRepository.findByName("ADMIN")
                .orElseGet(() -> roleRepository.save(new Role("ADMIN", "시스템 관리자")));
        Role userRole = roleRepository.findByName("USER")
                .orElseGet(() -> roleRepository.save(new Role("USER", "일반 사용자")));
        roleRepository.findByName("OPERATOR")
                .orElseGet(() -> roleRepository.save(new Role("OPERATOR", "운영자")));

        if (!userRepository.existsByEmail(adminEmail)) {
            User admin = new User(adminEmail,
                    passwordEncoder.encode(adminPassword),
                    adminName, "");
            admin.addRole(adminRole);
            admin.addRole(userRole);
            userRepository.save(admin);
            log.info("기본 관리자 계정 생성: {}", adminEmail);
        }
    }
}
