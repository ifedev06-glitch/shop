package com.example.shopsecure.exception;

public class EmailNotVerifiedException extends RuntimeException {
    public EmailNotVerifiedException() {
        super("Verify your email before signing in");
    }
}
