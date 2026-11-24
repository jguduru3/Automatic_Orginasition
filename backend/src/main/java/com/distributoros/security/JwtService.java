package com.distributoros.security;

import com.distributoros.config.AppProperties;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.Optional;

@Service
public class JwtService {
    private final SecretKey key;
    private final int ttlHours;

    public JwtService(AppProperties props) {
        byte[] bytes = props.jwt().secret().getBytes(StandardCharsets.UTF_8);
        if (bytes.length < 32) {
            throw new IllegalStateException("JWT_SECRET must be at least 32 bytes");
        }
        this.key = Keys.hmacShaKeyFor(bytes);
        this.ttlHours = props.jwt().ttlHours();
    }

    public String issue(AuthUser u) {
        Instant now = Instant.now();
        return Jwts.builder()
                .subject(Long.toString(u.userId()))
                .claim("tid", u.tenantId())
                .claim("role", u.role())
                .claim("name", u.name())
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(ttlHours, ChronoUnit.HOURS)))
                .signWith(key)
                .compact();
    }

    public Optional<AuthUser> verify(String token) {
        try {
            Claims c = Jwts.parser().verifyWith(key).build().parseSignedClaims(token).getPayload();
            long uid = Long.parseLong(c.getSubject());
            long tid = ((Number) c.get("tid")).longValue();
            String role = String.valueOf(c.get("role"));
            String name = String.valueOf(c.get("name"));
            return Optional.of(new AuthUser(uid, tid, role, name));
        } catch (JwtException | IllegalArgumentException | ClassCastException | NullPointerException e) {
            return Optional.empty();
        }
    }
}
