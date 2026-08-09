package com.example.shopsecure.controller;

import com.example.shopsecure.dto.response.CurrentUserResponse;
import com.example.shopsecure.service.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import jakarta.validation.Valid;
import com.example.shopsecure.dto.request.UpdateProfileRequest;

@RestController
@RequestMapping("/api/users")
@RequiredArgsConstructor
public class UserController {

    private final UserService userService;

    @GetMapping("/me")
    public CurrentUserResponse currentUser() {
        return userService.getCurrentUser();
    }

    @PatchMapping("/me")
    public CurrentUserResponse updateCurrentUser(@Valid @RequestBody UpdateProfileRequest request) {
        return userService.updateCurrentUser(request);
    }
}
