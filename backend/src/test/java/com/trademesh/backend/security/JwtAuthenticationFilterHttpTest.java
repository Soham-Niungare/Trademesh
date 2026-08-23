package com.trademesh.backend.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.trademesh.backend.controller.LoginRequest;
import com.trademesh.backend.controller.LoginResponse;
import com.trademesh.backend.controller.RegisterRequest;
import com.trademesh.backend.repository.UserRepository;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@code JwtServiceTest} already proves {@link JwtService#parse} rejects expired and
 * tampered tokens, but it calls the service directly. This class covers the part that
 * unit test structurally cannot: that {@link JwtAuthenticationFilter} catches those
 * throws and translates them into "unauthenticated" rather than letting them escape,
 * and that {@link RestAuthenticationEntryPoint} then renders a 401 in the same
 * {@code ErrorResponse} shape every other failure uses.
 *
 * <p>Each case must be indistinguishable from the others in the response: an expired
 * token, a forged one, and a malformed header all produce the same status and the
 * same message, so a caller learns nothing about why their token was refused.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class JwtAuthenticationFilterHttpTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"));

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private UserRepository userRepository;

    /** The same secret the running filter validates against, so the forged token below is signed correctly. */
    @Value("${jwt.secret}")
    private String jwtSecret;

    @AfterEach
    void cleanUp() {
        userRepository.deleteAll();
    }

    private ResultActions getOrdersWithAuthHeader(String headerValue) throws Exception {
        return mockMvc.perform(headerValue == null
                ? get("/api/orders")
                : get("/api/orders").header(HttpHeaders.AUTHORIZATION, headerValue));
    }

    /** Asserts the 401 came back in the project's single error shape, not a container default. */
    private void expectStandardUnauthorized(ResultActions result) throws Exception {
        result.andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.error").value("Unauthorized"))
                .andExpect(jsonPath("$.timestamp").exists())
                .andExpect(jsonPath("$.message").isNotEmpty());
    }

    @Test
    void expiredToken_returns401ThroughTheFilterChain() throws Exception {
        SecretKey key = Keys.hmacShaKeyFor(jwtSecret.getBytes(StandardCharsets.UTF_8));
        // Correctly signed by the real secret -- only the expiry makes it invalid,
        // so this exercises the ExpiredJwtException branch specifically rather than
        // failing on the signature first.
        String expired = Jwts.builder()
                .subject(UUID.randomUUID().toString())
                .claim("username", "expired-user")
                .issuedAt(Date.from(Instant.now().minus(Duration.ofHours(2))))
                .expiration(Date.from(Instant.now().minus(Duration.ofHours(1))))
                .signWith(key, Jwts.SIG.HS256)
                .compact();

        expectStandardUnauthorized(getOrdersWithAuthHeader("Bearer " + expired));
    }

    @Test
    void tamperedToken_returns401ThroughTheFilterChain() throws Exception {
        String valid = registerAndLogin();
        int lastChar = valid.length() - 1;
        char flipped = valid.charAt(lastChar) == 'a' ? 'b' : 'a';
        String tampered = valid.substring(0, lastChar) + flipped;

        expectStandardUnauthorized(getOrdersWithAuthHeader("Bearer " + tampered));
    }

    @Test
    void garbageToken_returns401ThroughTheFilterChain() throws Exception {
        expectStandardUnauthorized(getOrdersWithAuthHeader("Bearer not-a-jwt-at-all"));
    }

    /**
     * No {@code Bearer } prefix: the filter ignores the header entirely rather than
     * attempting to parse it, so this exercises the "never even looked at" branch.
     */
    @Test
    void authorizationHeaderWithoutBearerPrefix_returns401() throws Exception {
        String valid = registerAndLogin();

        expectStandardUnauthorized(getOrdersWithAuthHeader(valid));
    }

    @Test
    void noAuthorizationHeaderAtAll_returns401() throws Exception {
        expectStandardUnauthorized(getOrdersWithAuthHeader(null));
    }

    /**
     * Control case. Without this, every assertion above would still pass if the
     * filter simply rejected everything, which would prove nothing about the
     * rejection being specific to bad tokens.
     */
    @Test
    void validToken_isAcceptedByTheSameFilterChain() throws Exception {
        String valid = registerAndLogin();

        getOrdersWithAuthHeader("Bearer " + valid).andExpect(status().isOk());
    }

    private String registerAndLogin() throws Exception {
        String username = "jwt-http-" + UUID.randomUUID();
        String password = "password123";

        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new RegisterRequest(username, username + "@example.com", password))))
                .andExpect(status().isCreated());

        MvcResult result = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new LoginRequest(username, password))))
                .andExpect(status().isOk())
                .andReturn();

        return objectMapper.readValue(result.getResponse().getContentAsString(), LoginResponse.class).token();
    }
}
