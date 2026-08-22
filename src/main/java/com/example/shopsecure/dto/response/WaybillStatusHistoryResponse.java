package com.example.shopsecure.dto.response;

import com.example.shopsecure.entity.WaybillStatus;

import java.time.LocalDateTime;

public record WaybillStatusHistoryResponse(
        WaybillStatus fromStatus,
        WaybillStatus toStatus,
        String reason,
        LocalDateTime createdAt
) {
}
