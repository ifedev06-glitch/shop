package com.example.shopsecure.dto.response;

import lombok.AllArgsConstructor;
import lombok.Getter;

@AllArgsConstructor
@Getter
public class AuthResponse {

    private String token;
    private String message;
}
