package com.example.shopsecure.util;

import com.example.shopsecure.exception.BusinessException;

public final class NigerianPhoneNumber {

    public static final String INPUT_PATTERN = "^(?:(?:\\+?2340?)|(?:\\+?0))?[789]\\d{9}$";
    public static final String VALIDATION_MESSAGE = "Phone number must be a valid Nigerian number";

    private NigerianPhoneNumber() {
    }

    public static String normalize(String phoneNumber) {
        if (phoneNumber == null || phoneNumber.isBlank()) {
            throw new BusinessException(VALIDATION_MESSAGE);
        }

        String digits = phoneNumber.trim();
        if (digits.startsWith("+")) {
            digits = digits.substring(1);
        }
        if (!digits.chars().allMatch(Character::isDigit)) {
            throw new BusinessException(VALIDATION_MESSAGE);
        }

        if (digits.startsWith("2340")) {
            digits = digits.substring(4);
        } else if (digits.startsWith("234")) {
            digits = digits.substring(3);
        } else if (digits.startsWith("0")) {
            digits = digits.substring(1);
        }

        if (digits.length() != 10
                || (digits.charAt(0) != '7' && digits.charAt(0) != '8' && digits.charAt(0) != '9')) {
            throw new BusinessException(VALIDATION_MESSAGE);
        }
        return "+234" + digits;
    }
}
