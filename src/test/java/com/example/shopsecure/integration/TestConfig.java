package com.example.shopsecure.integration;

import com.example.shopsecure.config.RateLimitFilter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import java.io.IOException;

@TestConfiguration
public class TestConfig {

    @Bean
    @Primary
    public RateLimitFilter rateLimitFilter() {
        return new RateLimitFilter() {
            @Override
            protected void doFilterInternal(HttpServletRequest request,
                                            HttpServletResponse response,
                                            FilterChain chain)
                    throws ServletException, IOException {
                chain.doFilter(request, response);
            }
        };
    }
}
