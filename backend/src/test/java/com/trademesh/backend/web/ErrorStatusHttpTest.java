package com.trademesh.backend.web;

import com.trademesh.backend.controller.LoginRequest;
import com.trademesh.backend.controller.LoginResponse;
import com.trademesh.backend.controller.RegisterRequest;
import com.trademesh.backend.repository.OrderRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;

/**
 * Regression tests for the bug where every error without an {@code @ExceptionHandler}
 * came back as {@code 401 "Missing or invalid authentication token"} instead of its
 * real status -- including on {@code permitAll} routes, and including genuine server
 * faults.
 *
 * <p>The cause was a chain of three things: no handler for
 * {@code HttpMessageNotReadableException}, so Jackson failures escaped the dispatcher
 * and forwarded to {@code /error}; {@code /error} not being in the {@code permitAll}
 * list; and {@code OncePerRequestFilter.shouldNotFilterErrorDispatch()} defaulting to
 * {@code true}, so {@code JwtAuthenticationFilter} never ran on that second dispatch
 * and the request reached the authorization rules unauthenticated whatever token the
 * caller had sent.
 *
 * <p>This runs against a real embedded server (RANDOM_PORT) rather than MockMvc
 * deliberately: MockMvc has no ERROR dispatch at all -- it rethrows an unhandled
 * exception to the caller instead of forwarding to {@code /error} -- so the exact
 * mechanism this bug lived in is invisible to it, and a MockMvc test would have
 * passed both before and after the fix.
 *
 * <p><b>If these ever fail again, read the failure carefully.</b> Verified against
 * the pre-fix code, they do not fail with a tidy "expected 400 but was 401": the
 * 401 makes the JDK's {@code HttpURLConnection} attempt an authentication retry it
 * cannot perform on an already-streamed body, so the assertion is never reached and
 * the error reads {@code "I/O error ... cannot retry due to server authentication,
 * in streaming mode"}. That message means the server answered 401 -- i.e. this
 * regression -- not that the test's HTTP plumbing is broken.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class ErrorStatusHttpTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"));

    @Autowired
    private TestRestTemplate restTemplate;

    /**
     * Only used by the 500 test, to force a fault no {@code @ExceptionHandler} claims.
     *
     * <p>A repository interface rather than the more obvious choice of mocking
     * {@code TradeService} directly: Mockito's inline mock maker cannot instrument
     * concrete classes on the JDK this build runs under (Byte Buddy fails with
     * "could not instrument all classes within the mock's type hierarchy"), while
     * Spring Data interfaces mock fine. {@code TradeServiceTest} spies
     * {@code TradeRepository} the same way for the same underlying reason.
     */
    @SpyBean
    private OrderRepository orderRepository;

    private String registerAndLogin() {
        String username = "err-" + UUID.randomUUID();
        String password = "password123";
        restTemplate.postForEntity("/api/auth/register",
                new RegisterRequest(username, username + "@example.com", password), Void.class);
        return restTemplate.postForObject("/api/auth/login",
                new LoginRequest(username, password), LoginResponse.class).token();
    }

    private ResponseEntity<String> postRaw(String path, String rawBody, String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (token != null) {
            headers.setBearerAuth(token);
        }
        return restTemplate.exchange(path, HttpMethod.POST, new HttpEntity<>(rawBody, headers), String.class);
    }

    private static void assertNotTheAuthError(ResponseEntity<String> response) {
        assertNotEquals(HttpStatus.UNAUTHORIZED, response.getStatusCode(),
                () -> "regression: error reported as an auth failure -- body was " + response.getBody());
    }

    @Test
    void malformedJson_onAuthenticatedRoute_returns400NotUnauthorized() {
        String token = registerAndLogin();

        ResponseEntity<String> response = postRaw("/api/orders", "{not valid json", token);

        assertNotTheAuthError(response);
        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
    }

    /**
     * The sharpest form of the bug: this route is {@code permitAll}, so a 401 here
     * was not merely the wrong status but a claim about authentication on an
     * endpoint that requires none -- and it is the endpoint a client would be
     * calling precisely because it has no token yet.
     */
    @Test
    void malformedJson_onPublicLoginRoute_returns400NotUnauthorized() {
        ResponseEntity<String> response = postRaw("/api/auth/login", "{not valid json", null);

        assertNotTheAuthError(response);
        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
    }

    /** Same handler, different trigger: a well-formed body whose enum value doesn't exist. */
    @Test
    void unknownEnumValue_returns400NotUnauthorized() {
        String token = registerAndLogin();
        String body = """
                {"symbol":"SYM","side":"SIDEWAYS","type":"LIMIT","price":100,"quantity":10}
                """;

        ResponseEntity<String> response = postRaw("/api/orders", body, token);

        assertNotTheAuthError(response);
        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
    }

    /**
     * A genuine server fault must stay a server fault. {@code IllegalStateException}
     * is the realistic choice here -- {@code TradeService} throws exactly that when a
     * resting order matched in memory has no DB row -- and nothing maps it, so it
     * takes the same escaped-to-/error path the malformed-body cases used to take.
     *
     * <p>The order is placed into a fresh symbol with no liquidity, so the only
     * {@code findById} on the request path is {@code OrderController}'s own read-back
     * of the order it just created: the failure lands after the write succeeded,
     * which is exactly the "unexpected fault mid-request" shape being tested.
     */
    @Test
    void unhandledServerException_returns500NotUnauthorized() {
        String token = registerAndLogin();
        doThrow(new IllegalStateException("simulated unexpected server fault"))
                .when(orderRepository).findById(any(UUID.class));

        String body = """
                {"symbol":"SYM-%s","side":"BUY","type":"LIMIT","price":100,"quantity":10}
                """.formatted(UUID.randomUUID());
        ResponseEntity<String> response = postRaw("/api/orders", body, token);

        assertNotTheAuthError(response);
        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.getStatusCode());
    }

    /**
     * Wider blast radius of the same cause, worth locking in: Spring MVC sets 405
     * without ever throwing into a controller, so this never had an
     * {@code @ExceptionHandler} to reach and went to {@code /error} too.
     */
    @Test
    void unsupportedHttpMethod_returns405NotUnauthorized() {
        ResponseEntity<String> response = restTemplate.exchange(
                "/api/auth/login", HttpMethod.PUT, new HttpEntity<>(new HttpHeaders()), String.class);

        assertNotTheAuthError(response);
        assertEquals(HttpStatus.METHOD_NOT_ALLOWED, response.getStatusCode());
    }
}
