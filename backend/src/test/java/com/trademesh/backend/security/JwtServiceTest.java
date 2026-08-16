package com.trademesh.backend.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.Test;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** No Spring context needed -- JwtService is a plain class, exercisable directly. */
class JwtServiceTest {

    private static final String SECRET = "test-only-secret-key-that-is-at-least-32-bytes-long-for-hs256";
    private static final long EXPIRATION_MILLIS = 3_600_000L;

    private final JwtService jwtService = new JwtService(SECRET, EXPIRATION_MILLIS);

    @Test
    void issueThenParse_roundTripsUserIdAndUsername() {
        UUID userId = UUID.randomUUID();

        String token = jwtService.issue(userId, "alice");
        Claims claims = jwtService.parse(token);

        assertEquals(userId.toString(), claims.getSubject());
        assertEquals("alice", claims.get("username", String.class));
    }

    @Test
    void parsingExpiredToken_throws() {
        SecretKey key = Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8));
        String expiredToken = Jwts.builder()
                .subject(UUID.randomUUID().toString())
                .issuedAt(Date.from(Instant.now().minus(Duration.ofHours(2))))
                .expiration(Date.from(Instant.now().minus(Duration.ofHours(1))))
                .signWith(key, Jwts.SIG.HS256)
                .compact();

        assertThrows(ExpiredJwtException.class, () -> jwtService.parse(expiredToken));
    }

    @Test
    void parsingTamperedToken_throws() {
        String token = jwtService.issue(UUID.randomUUID(), "alice");

        int lastChar = token.length() - 1;
        char flipped = token.charAt(lastChar) == 'a' ? 'b' : 'a';
        String tampered = token.substring(0, lastChar) + flipped;

        assertThrows(JwtException.class, () -> jwtService.parse(tampered));
    }
}
