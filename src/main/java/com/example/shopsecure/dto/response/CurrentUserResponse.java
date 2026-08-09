package com.example.shopsecure.dto.response;

import com.example.shopsecure.entity.Role;

public record CurrentUserResponse(
        Long userId,
        String firstname,
        String lastname,
        String email,
        String phoneNumber,
        Role role,
        WalletResponse wallet,
        VirtualAccountDetailsResponse virtualAccount
) {
}
