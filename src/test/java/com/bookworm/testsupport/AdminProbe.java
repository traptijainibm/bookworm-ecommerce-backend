package com.bookworm.testsupport;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Test-only stand-in for the Phase 3 admin API, so Phase 2 can prove that {@code /api/v1/admin/**} requires
 * ROLE_ADMIN. {@code @TestConfiguration} keeps it out of component scanning (Spring Boot's test type filter
 * excludes it), so it exists only in tests that {@code @Import} it and never in the application or its OpenAPI
 * document. {@code @RestController} is still required: Spring 7 no longer treats a bare {@code @RequestMapping}
 * as a handler.
 */
@TestConfiguration
@RestController
@RequestMapping("/api/v1/admin")
public class AdminProbe {

    @GetMapping("/probe")
    public String probe() {
        return "admin-ok";
    }
}
