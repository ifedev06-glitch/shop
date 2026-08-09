package com.example.shopsecure.config;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ExplicitProfileValidatorTest {

    @ParameterizedTest
    @ValueSource(strings = {"local", "test", "prod"})
    void acceptsExactlyOneSupportedProfile(String profile) {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles(profile);

        assertThatCode(() -> new ExplicitProfileValidator(environment)).doesNotThrowAnyException();
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "prodd", "prod,local"})
    void rejectsMissingUnknownAndCombinedProfiles(String profiles) {
        MockEnvironment environment = new MockEnvironment();
        if (!profiles.isBlank()) {
            environment.setActiveProfiles(profiles.split(","));
        }

        assertThatThrownBy(() -> new ExplicitProfileValidator(environment))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Set exactly one SPRING_PROFILES_ACTIVE value: local, test, or prod");
    }
}
