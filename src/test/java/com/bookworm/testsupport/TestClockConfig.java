package com.bookworm.testsupport;

import java.time.Instant;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/** Replaces the production clock with one tests can advance (it is {@code @Primary}, so every Clock injection gets it). */
@TestConfiguration
public class TestClockConfig {

    @Bean
    @Primary
    public MutableClock testClock() {
        return new MutableClock(Instant.now());
    }
}
