package com.trademesh.backend.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.trademesh.backend.repository.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class AuthControllerTest {

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

    @AfterEach
    void cleanUp() {
        userRepository.deleteAll();
    }

    private static String uniqueUsername() {
        return "user-" + UUID.randomUUID();
    }

    private static String uniqueEmail() {
        return UUID.randomUUID() + "@example.com";
    }

    private void register(String username, String email, String password) throws Exception {
        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new RegisterRequest(username, email, password))))
                .andExpect(status().isCreated());
    }

    @Test
    void register_success_returns201WithUserId() throws Exception {
        String body = objectMapper.writeValueAsString(new RegisterRequest(uniqueUsername(), uniqueEmail(), "password123"));

        mockMvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.userId").exists());
    }

    @Test
    void register_duplicateUsername_returns409MentioningUsername() throws Exception {
        String username = uniqueUsername();
        register(username, uniqueEmail(), "password123");

        String body = objectMapper.writeValueAsString(new RegisterRequest(username, uniqueEmail(), "password123"));
        mockMvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsStringIgnoringCase("username")));
    }

    @Test
    void register_duplicateEmail_returns409MentioningEmail() throws Exception {
        String email = uniqueEmail();
        register(uniqueUsername(), email, "password123");

        String body = objectMapper.writeValueAsString(new RegisterRequest(uniqueUsername(), email, "password123"));
        mockMvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsStringIgnoringCase("email")));
    }

    @Test
    void login_success_returnsValidNonEmptyToken() throws Exception {
        String username = uniqueUsername();
        register(username, uniqueEmail(), "password123");

        String body = objectMapper.writeValueAsString(new LoginRequest(username, "password123"));
        MvcResult result = mockMvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andReturn();

        LoginResponse response = objectMapper.readValue(result.getResponse().getContentAsString(), LoginResponse.class);
        assertFalse(response.token().isBlank());
        assertTrue(response.expiresIn() > 0);
    }

    @Test
    void login_wrongPasswordAndUnknownUsername_bothReturnTheSame401() throws Exception {
        String username = uniqueUsername();
        register(username, uniqueEmail(), "password123");

        MvcResult wrongPassword = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new LoginRequest(username, "wrong-password"))))
                .andExpect(status().isUnauthorized())
                .andReturn();

        MvcResult unknownUsername = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new LoginRequest("no-such-user-" + UUID.randomUUID(), "whatever"))))
                .andExpect(status().isUnauthorized())
                .andReturn();

        // Same status already asserted above; also assert the message body doesn't
        // leak which case it was, so a caller genuinely can't tell them apart.
        String wrongPasswordMessage = objectMapper.readTree(wrongPassword.getResponse().getContentAsString()).get("message").asText();
        String unknownUsernameMessage = objectMapper.readTree(unknownUsername.getResponse().getContentAsString()).get("message").asText();
        assertEquals(wrongPasswordMessage, unknownUsernameMessage);
    }
}
