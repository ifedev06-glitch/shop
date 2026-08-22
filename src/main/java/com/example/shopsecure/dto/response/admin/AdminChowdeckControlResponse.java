package com.example.shopsecure.dto.response.admin;

import com.example.shopsecure.entity.ChowdeckMode;

import java.time.Instant;

public record AdminChowdeckControlResponse(
        ChowdeckMode configuredMode,
        ChowdeckMode effectiveMode,
        boolean hardKillActive,
        long version,
        Long updatedByAdminId,
        Instant updatedAt
) {
}
