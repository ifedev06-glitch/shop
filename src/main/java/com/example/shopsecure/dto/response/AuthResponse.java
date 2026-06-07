package com.example.shopsecure.dto.response;

import com.example.shopsecure.entity.Role;
import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class AuthResponse {

    private String token;
    private String message;

    private Long userId;
    private String firstname;
    private String lastname;
    private String email;
    private Role role;
}