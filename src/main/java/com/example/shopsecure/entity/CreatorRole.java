package com.example.shopsecure.entity;

public enum CreatorRole {
    BUYER,
    VENDOR;

    public CreatorRole opposite() {
        return this == BUYER ? VENDOR : BUYER;
    }
}
