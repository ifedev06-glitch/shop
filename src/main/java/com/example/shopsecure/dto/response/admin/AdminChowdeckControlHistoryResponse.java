package com.example.shopsecure.dto.response.admin;

import com.example.shopsecure.entity.ChowdeckMode;

import java.time.Instant;

public record AdminChowdeckControlHistoryResponse(
        long id,
        ChowdeckMode fromMode,
        ChowdeckMode toMode,
        String reason,
        long changedByAdminId,
        Instant changedAt,
        long controlVersion
) {
}
