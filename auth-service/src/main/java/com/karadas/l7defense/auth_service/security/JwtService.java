package com.karadas.l7defense.auth_service.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;

@Component
public class JwtService {

    private final SecretKey key;
    private static final Duration TOKEN_LIFETIME = Duration.ofMinutes(15);

    public JwtService(@Value("${app.jwt.secret}") String secret) {
        this.key = Keys.hmacShaKeyFor(secret.getBytes());
    }

    public String generateToken(Long memberId, String username) {
        Instant now = Instant.now();
        return Jwts.builder()
                .subject(username)
                .claim("memberId", memberId)
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(TOKEN_LIFETIME)))
                .signWith(key, Jwts.SIG.HS256)
                .compact();
    }

}