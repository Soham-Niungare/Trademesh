package com.trademesh.backend.controller;

import com.trademesh.backend.service.AuthService;
import com.trademesh.backend.service.LoginResult;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    @PostMapping("/register")
    public ResponseEntity<RegisterResponse> register(@Valid @RequestBody RegisterRequest request) {
        UUID userId = authService.register(request.username(), request.email(), request.password());
        return ResponseEntity.status(HttpStatus.CREATED).body(new RegisterResponse(userId));
    }

    @PostMapping("/login")
    public LoginResponse login(@Valid @RequestBody LoginRequest request) {
        LoginResult result = authService.login(request.username(), request.password());
        return new LoginResponse(result.token(), result.expiresIn());
    }
}
