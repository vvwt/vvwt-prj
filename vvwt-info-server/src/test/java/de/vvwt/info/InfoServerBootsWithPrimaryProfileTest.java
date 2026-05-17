// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
package de.vvwt.info;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

/**
 * AC8 (security) — Verifies that starting with spring.profiles.active=primary activates the
 * 'primary' profile and boots cleanly.
 *
 * <p>DEC-42 D3: both profiles must boot cleanly (AC10). This test verifies the primary profile is
 * active when explicitly requested, and that the application starts without errors.
 *
 * <p>DataSource override: the primary profile normally requires PostgreSQL, but for this test we
 * override to an in-memory H2 datasource so the test is runnable without a live PostgreSQL instance
 * in CI and local dev. This is a test-only override; production primary deployments use PostgreSQL
 * per DEC-42 D4.
 *
 * <p>Story: E38S01 — DEC-42 D3, D4.
 */
@SpringBootTest(
        classes = InfoServerApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("primary")
@TestPropertySource(
        properties = {
            // Override PostgreSQL config to H2 for test environment (DEC-42 D4 test-only seam)
            "spring.datasource.url=jdbc:h2:mem:vvwt-info-primary-test;DB_CLOSE_DELAY=-1;CASE_INSENSITIVE_IDENTIFIERS=TRUE",
            "spring.datasource.driver-class-name=org.h2.Driver",
            "spring.datasource.username=sa",
            "spring.datasource.password=",
            // Override Flyway locations: primary profile points to postgresql/ which requires a
            // real
            // PostgreSQL engine. For this boot-only context test we use the H2-compatible
            // migrations
            // so the schema can be created on the H2 in-memory DB.
            "spring.flyway.locations=classpath:db/migration/h2,classpath:db/migration/common",
        })
class InfoServerBootsWithPrimaryProfileTest {

    @Autowired private Environment env;

    @Test
    void applicationBootsWithPrimaryProfile() {
        // AC8: with @ActiveProfiles("primary"), 'primary' must be the active profile
        assertThat(Arrays.asList(env.getActiveProfiles()))
                .as(
                        "Expected 'primary' to be in active profiles when"
                            + " @ActiveProfiles(\"primary\") is set (DEC-42 D3). activeProfiles=%s",
                        Arrays.toString(env.getActiveProfiles()))
                .contains("primary");
    }
}
