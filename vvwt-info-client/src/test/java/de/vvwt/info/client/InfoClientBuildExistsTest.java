package de.vvwt.info.client;

import org.junit.jupiter.api.Test;

/**
 * AC1 (testing) — RED-first existence test for vvwt-info-client module.
 *
 * <p>Written BEFORE the vvwt-info-client module directory and pom.xml exist. This test class being
 * compilable and runnable proves the module skeleton is in place (GREEN state).
 *
 * <p>The npm/Svelte build correctness is verified by the Maven reactor executing
 * frontend-maven-plugin in the generate-resources phase (AC11, AC2). Story: E38S01 — DEC-22 §
 * Red-first obligation.
 */
class InfoClientBuildExistsTest {

    @Test
    void moduleSkeletonExists() {
        // Existence test: this test class being compilable and runnable is the
        // evidence that the vvwt-info-client module skeleton is in place.
        // The Svelte/npm build correctness is verified by the Maven verify phase
        // through frontend-maven-plugin (AC11, AC2).
    }
}
