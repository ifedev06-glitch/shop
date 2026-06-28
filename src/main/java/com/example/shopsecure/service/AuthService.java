package com.example.shopsecure.service;

import com.example.shopsecure.dto.response.AuthResponse;
import com.example.shopsecure.dto.request.LoginRequest;
import com.example.shopsecure.dto.request.RegisterRequest;
import com.example.shopsecure.entity.User;
import com.example.shopsecure.entity.Role;
import com.example.shopsecure.exception.BusinessException;
import com.example.shopsecure.exception.ResourceNotFoundException;
import com.example.shopsecure.repository.UserRepository;
import com.example.shopsecure.security.JwtService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

@Service
@RequiredArgsConstructor
@Slf4j
public class AuthService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final AuthenticationManager authenticationManager;

    public AuthResponse register(RegisterRequest request) {

        if (userRepository.existsByEmail(request.getEmail())) {
            log.warn("Registration failed - email already exists: email={}", request.getEmail());
            throw new BusinessException("Email already registered"); // ← was RuntimeException
        }

        User user = User.builder()
                .firstname(request.getFirstname())
                .lastname(request.getLastname())
                .email(request.getEmail())
                .password(passwordEncoder.encode(request.getPassword()))
                .role(Role.BUSINESS)
                .createdAt(LocalDateTime.now())
                .build();

        userRepository.save(user);

        String token = jwtService.generateToken(user);
        log.info("Merchant registered successfully: userId={}, email={}", user.getId(), user.getEmail());

        return AuthResponse.builder()
                .token(token)
                .message("Account Created Successfully")
                .userId(user.getId())
                .firstname(user.getFirstname())
                .lastname(user.getLastname())
                .email(user.getEmail())
                .role(user.getRole())
                .build();
    }

    public AuthResponse login(LoginRequest request) {

        try {
            authenticationManager.authenticate(
                    new UsernamePasswordAuthenticationToken(
                            request.getEmail(),
                            request.getPassword()
                    )
            );
        } catch (BadCredentialsException e) {
            log.warn("Login failed - invalid credentials: email={}", request.getEmail());
            throw e;
        }

        User user = userRepository.findByEmail(request.getEmail())
                .orElseThrow(() -> new ResourceNotFoundException("User not found")); // ← was RuntimeException

        String token = jwtService.generateToken(user);
        log.info("Merchant logged in successfully: userId={}, email={}", user.getId(), user.getEmail());

        return AuthResponse.builder()
                .token(token)
                .message("Login Successful")
                .userId(user.getId())
                .firstname(user.getFirstname())
                .lastname(user.getLastname())
                .email(user.getEmail())
                .role(user.getRole())
                .build();
    }
}