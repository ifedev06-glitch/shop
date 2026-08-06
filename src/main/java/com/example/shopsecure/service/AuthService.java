package com.example.shopsecure.service;

import com.example.shopsecure.dto.response.AuthResponse;
import com.example.shopsecure.dto.request.LoginRequest;
import com.example.shopsecure.dto.request.RegisterRequest;
import com.example.shopsecure.entity.User;
import com.example.shopsecure.entity.Role;
import com.example.shopsecure.entity.Wallet;
import com.example.shopsecure.exception.BusinessException;
import com.example.shopsecure.exception.ResourceNotFoundException;
import com.example.shopsecure.repository.UserRepository;
import com.example.shopsecure.repository.WalletRepository;
import com.example.shopsecure.security.JwtService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;

@Service
@RequiredArgsConstructor
@Slf4j
public class AuthService {

    private final UserRepository userRepository;
    private final WalletRepository walletRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final AuthenticationManager authenticationManager;

    @Transactional
    public AuthResponse register(RegisterRequest request) {

        if (userRepository.existsByEmail(request.getEmail())) {
            log.warn("Registration failed - email already exists: email={}", request.getEmail());
            throw new BusinessException("Email already registered");
        }

        User user = User.builder()
                .firstname(request.getFirstname())
                .lastname(request.getLastname())
                .email(request.getEmail())
                .password(passwordEncoder.encode(request.getPassword()))
                .role(Role.USER)
                .createdAt(LocalDateTime.now())
                .build();

        userRepository.save(user);
        walletRepository.save(Wallet.builder()
                .user(user)
                .availableBalance(BigDecimal.ZERO.setScale(2))
                .processingBalance(BigDecimal.ZERO.setScale(2))
                .currency("NGN")
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build());

        String token = jwtService.generateToken(user);
        log.info("Account registered successfully: userId={}, email={}", user.getId(), user.getEmail());

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
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));

        String token = jwtService.generateToken(user);
        log.info("Account logged in successfully: userId={}, email={}", user.getId(), user.getEmail());

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
