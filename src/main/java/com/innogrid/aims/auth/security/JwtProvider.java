package com.innogrid.aims.auth.security;

import java.util.Date;
import java.util.stream.Collectors;

import javax.crypto.SecretKey;

import org.springframework.stereotype.Component;

import com.innogrid.aims.auth.entity.Role;
import com.innogrid.aims.auth.entity.User;

import io.jsonwebtoken.*;
import io.jsonwebtoken.security.Keys;

@Component
public class JwtProvider {

    private final SecretKey key;
    private final long accessExpirationMs;
    private final long refreshExpirationMs;

    public JwtProvider(JwtProperties props) {
        this.key = Keys.hmacShaKeyFor(props.getSecret().getBytes());
        this.accessExpirationMs = props.getAccessExpirationMs();
        this.refreshExpirationMs = props.getRefreshExpirationMs();
    }

    public String generateAccessToken(User user) {
        return buildToken(user, accessExpirationMs);
    }

    public String generateRefreshToken(User user) {
        return buildToken(user, refreshExpirationMs);
    }

    private String buildToken(User user, long expirationMs) {
        Date now = new Date();
        String roles = user.getRoles().stream()
                .map(Role::getName)
                .collect(Collectors.joining(","));

        return Jwts.builder()
                .subject(user.getEmail())
                .claim("userId", user.getId())
                .claim("name", user.getName())
                .claim("roles", roles)
                .claim("organization", user.getOrganization())
                .issuedAt(now)
                .expiration(new Date(now.getTime() + expirationMs))
                .signWith(key)
                .compact();
    }

    public String getEmailFromToken(String token) {
        return parseClaims(token).getSubject();
    }

    public boolean validateToken(String token) {
        try {
            parseClaims(token);
            return true;
        } catch (JwtException | IllegalArgumentException e) {
            return false;
        }
    }

    public Claims parseClaims(String token) {
        return Jwts.parser()
                .verifyWith(key)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }
}
