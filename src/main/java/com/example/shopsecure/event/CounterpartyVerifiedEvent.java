package com.example.shopsecure.event;

import com.example.shopsecure.entity.CreatorRole;

public record CounterpartyVerifiedEvent(
        String creatorEmail,
        String creatorFirstName,
        String itemDescription,
        CreatorRole counterpartyRole
) {
}
