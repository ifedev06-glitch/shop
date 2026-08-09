package com.example.shopsecure.integration;

import com.example.shopsecure.config.RateLimitFilter;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.core.task.SyncTaskExecutor;
import org.springframework.core.task.TaskExecutor;

@TestConfiguration
public class TestConfig {

    @Bean
    @Primary
    public RateLimitFilter rateLimitFilter() {
        return new RateLimitFilter(false);
    }

    @Bean(name = "taskExecutor")
    @Primary
    public TaskExecutor taskExecutor() {
        return new SyncTaskExecutor();
    }
}
