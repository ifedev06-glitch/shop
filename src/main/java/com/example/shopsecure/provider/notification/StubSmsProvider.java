package com.example.shopsecure.provider.notification;

public final class StubSmsProvider implements SmsProvider {

    @Override
    public void sendMessage(String phoneNumber, String message) {
        // Intentionally discard local and test messages without retaining sensitive values.
    }
}
