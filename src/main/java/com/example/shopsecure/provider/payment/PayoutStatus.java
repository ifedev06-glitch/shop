package com.example.shopsecure.provider.payment;

public enum PayoutStatus {
    PENDING,
    REQUIRES_ACTION,
    SUCCESS,
    FAILED,
    REVERSED,
    UNKNOWN
}
