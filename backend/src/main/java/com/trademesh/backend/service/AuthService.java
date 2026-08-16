package com.trademesh.backend.service;

import com.trademesh.backend.entity.User;
import com.trademesh.backend.exception.DuplicateUserException;
import com.trademesh.backend.exception.InvalidCredentialsException;
import com.trademesh.backend.repository.UserRepository;
import com.trademesh.backend.security.JwtService;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.time.Instant;
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

    public UUID register(String username, String email, String password) {
        if (userRepository.findByUsername(username).isPresent()) {
            throw new DuplicateUserException("username already exists");
        }
        if (userRepository.findByEmail(email).isPresent()) {
            throw new DuplicateUserException("email already exists");
        }

        User user = new User();
        user.setUsername(username);
        user.setEmail(email);
        user.setPasswordHash(passwordEncoder.encode(password));
        user.setCreatedAt(Instant.now());
        userRepository.save(user);

        return user.getId();
    }

    public LoginResult login(String username, String password) {
        User user = userRepository.findByUsername(username).orElse(null);
        // Same exception (and message) for "no such user" and "wrong password" --
        // distinguishing them would let a caller enumerate valid usernames.
        if (user == null || !passwordEncoder.matches(password, user.getPasswordHash())) {
            throw new InvalidCredentialsException("invalid username or password");
        }

        String token = jwtService.issue(user.getId(), user.getUsername());
        return new LoginResult(token, jwtService.expirySeconds());
    }
}
