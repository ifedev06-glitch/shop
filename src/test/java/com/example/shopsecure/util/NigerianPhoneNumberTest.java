package com.example.shopsecure.util;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class NigerianPhoneNumberTest {

    @Test
    void normalizesSupportedNigerianFormats() {
        assertThat(NigerianPhoneNumber.normalize("08051234567")).isEqualTo("+2348051234567");
        assertThat(NigerianPhoneNumber.normalize("+08051234567")).isEqualTo("+2348051234567");
        assertThat(NigerianPhoneNumber.normalize("2348051234567")).isEqualTo("+2348051234567");
        assertThat(NigerianPhoneNumber.normalize("+23408051234567")).isEqualTo("+2348051234567");
        assertThat(NigerianPhoneNumber.normalize("+2348051234567")).isEqualTo("+2348051234567");
    }

    @Test
    void rejectsNumbersThatCannotBeCanonicalized() {
        assertThatThrownBy(() -> NigerianPhoneNumber.normalize("+15005550006"))
                .hasMessage(NigerianPhoneNumber.VALIDATION_MESSAGE);
        assertThatThrownBy(() -> NigerianPhoneNumber.normalize("12345"))
                .hasMessage(NigerianPhoneNumber.VALIDATION_MESSAGE);
    }
}
