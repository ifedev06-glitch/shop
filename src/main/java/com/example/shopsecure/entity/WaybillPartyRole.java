package com.example.shopsecure.entity;

public enum WaybillPartyRole {
    SENDER,
    RECIPIENT;

    public WaybillPartyRole opposite() {
        return switch (this) {
            case SENDER -> RECIPIENT;
            case RECIPIENT -> SENDER;
        };
    }
}
