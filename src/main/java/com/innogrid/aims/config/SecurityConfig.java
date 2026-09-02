package com.innogrid.aims.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

import com.innogrid.aims.auth.security.JwtAuthFilter;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

    private final JwtAuthFilter jwtAuthFilter;

    public SecurityConfig(JwtAuthFilter jwtAuthFilter) {
        this.jwtAuthFilter = jwtAuthFilter;
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
                .csrf(csrf -> csrf.disable())
                .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        // Swagger UI
                        .requestMatchers("/swagger-ui/**", "/v3/api-docs/**").permitAll()
                        // 헬스체크, 핑
                        .requestMatchers("/api/ping", "/actuator/**").permitAll()
                        // 인증 API (로그인, 회원가입, 토큰 검증)
                        .requestMatchers("/api/v1/auth/**").permitAll()
                        // H2 콘솔 (로컬 개발용). 경로 문자열로 여는 이유:
                        // PathRequest.toH2Console() 은 콘솔이 켜진 환경에서만 존재하는 H2ConsoleProperties 빈을
                        // 요청 시점에 찾는다. 콘솔이 꺼진 테스트·운영에서는 그 빈이 없어 모든 요청이 보안 필터에서
                        // 예외가 났다(2026-09-02 회귀). 문자열 매처는 콘솔이 있든 없든 부작용이 없다.
                        // 로컬 콘솔이 뜨게 하는 것은 spring-boot-h2console 의존성이며, 그것은 그대로 둔다.
                        .requestMatchers("/h2-console/**").permitAll()
                        // 나머지는 인증 필요
                        .anyRequest().authenticated()
                )
                .headers(headers -> headers.frameOptions(fo -> fo.sameOrigin()))
                .addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public AuthenticationManager authenticationManager(AuthenticationConfiguration config) throws Exception {
        return config.getAuthenticationManager();
    }
}
