package com.example.shopsecure.config;

import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.util.Set;

@Component
public class ExplicitProfileValidator {

    private static final Set<String> ALLOWED_PROFILES = Set.of("local", "test", "loadtest", "prod");

    public ExplicitProfileValidator(Environment environment) {
        String[] activeProfiles = environment.getActiveProfiles();
        if (activeProfiles.length != 1 || !ALLOWED_PROFILES.contains(activeProfiles[0])) {
            throw new IllegalStateException(
                    "Set exactly one SPRING_PROFILES_ACTIVE value: local, test, loadtest, or prod"
            );
        }
    }
}
