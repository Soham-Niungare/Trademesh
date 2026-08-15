package com.trademesh.auth.service;

import com.trademesh.auth.dto.LoginRequest;
import com.trademesh.auth.dto.LoginResponse;
import com.trademesh.auth.dto.RegisterRequest;
import com.trademesh.auth.dto.RegisterResponse;
import com.trademesh.auth.dto.ValidateResponse;
import com.trademesh.auth.entity.User;
import com.trademesh.auth.exception.InvalidCredentialsException;
import com.trademesh.auth.exception.UserAlreadyExistsException;
import com.trademesh.auth.repository.UserRepository;
import com.trademesh.auth.security.JwtService;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
public class AuthService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;

    public AuthService(UserRepository userRepository, PasswordEncoder passwordEncoder, JwtService jwtService) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
    }

    @Transactional
    public RegisterResponse register(RegisterRequest request) {
        if (userRepository.existsByUsername(request.username())) {
            throw new UserAlreadyExistsException("Username already taken: " + request.username());
        }
        if (userRepository.existsByEmail(request.email())) {
            throw new UserAlreadyExistsException("Email already registered: " + request.email());
        }

        User user = new User();
        user.setUsername(request.username());
        user.setEmail(request.email());
        user.setPasswordHash(passwordEncoder.encode(request.password()));
        user = userRepository.save(user);

        return new RegisterResponse(user.getId());
    }

    public LoginResponse login(LoginRequest request) {
        User user = userRepository.findByUsername(request.username())
                .orElseThrow(() -> new InvalidCredentialsException("Invalid username or password"));

        if (!passwordEncoder.matches(request.password(), user.getPasswordHash())) {
            throw new InvalidCredentialsException("Invalid username or password");
        }

        String token = jwtService.generateToken(user.getId(), user.getUsername());
        return new LoginResponse(token, jwtService.getExpirationSeconds());
    }

    public ValidateResponse validate(String authorizationHeader) {
        if (authorizationHeader == null || !authorizationHeader.startsWith("Bearer ")) {
            throw new InvalidCredentialsException("Missing or malformed Authorization header");
        }
        String token = authorizationHeader.substring("Bearer ".length());
        try {
            Claims claims = jwtService.parseToken(token);
            UUID userId = UUID.fromString(claims.getSubject());
            String username = claims.get("username", String.class);
            return new ValidateResponse(userId, username);
        } catch (JwtException | IllegalArgumentException ex) {
            throw new InvalidCredentialsException("Invalid or expired token");
        }
    }
}
