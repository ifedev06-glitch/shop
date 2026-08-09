package com.example.shopsecure.provider.notification;

public interface SmsProvider {

    void sendMessage(String phoneNumber, String message);
}
