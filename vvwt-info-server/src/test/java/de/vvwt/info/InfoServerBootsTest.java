package de.vvwt.info;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;

/**
 * AC1 + AC8 (testing, security) — RED-first existence + default-profile assertion for
 * vvwt-info-server.
 *
 * <p>AC1: Written BEFORE InfoServerApplication.java exists — RED state is compile failure. GREEN
 * state: after InfoServerApplication skeleton is created and module builds.
 *
 * <p>AC8: Verifies that starting the application with NO explicit active profile results in the
 * 'self-host' profile being active (DEC-42 D3 drive-by-squatting-mitigation guarantee).
 *
 * <p>Story: E38S01 — DEC-22 § Red-first obligation, DEC-42 D3.
 */
@SpringBootTest(
        classes = InfoServerApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE)
class InfoServerBootsTest {

    @Autowired private Environment env;

    @Test
    void applicationBootsWithDefaultSelfHostProfile() {
        // AC8: no active profile set → self-host must be the active or default profile
        String[] activeProfiles = env.getActiveProfiles();
        String[] defaultProfiles = env.getDefaultProfiles();

        // With spring.profiles.default=self-host: when no spring.profiles.active is set,
        // Spring activates the default profile. We check that 'self-host' appears in either
        // active or default profiles.
        boolean selfHostIsPresent =
                Arrays.asList(activeProfiles).contains("self-host")
                        || Arrays.asList(defaultProfiles).contains("self-host");

        assertThat(selfHostIsPresent)
                .as(
                        "Expected 'self-host' to be the active or default profile when no"
                                + " explicit spring.profiles.active is set (DEC-42 D3)."
                                + " activeProfiles=%s, defaultProfiles=%s",
                        Arrays.toString(activeProfiles), Arrays.toString(defaultProfiles))
                .isTrue();
    }
}
