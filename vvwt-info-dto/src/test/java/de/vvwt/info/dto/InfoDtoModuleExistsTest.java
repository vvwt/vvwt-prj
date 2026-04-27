package de.vvwt.info.dto;

import org.junit.jupiter.api.Test;

/**
 * AC1 (testing) — RED-first existence test for vvwt-info-dto module.
 *
 * <p>This test was written BEFORE any production class in the module. It fails to compile when the
 * module directory/pom does not exist, providing the DEC-22 Iron Law RED state. Once the module
 * skeleton is in place, this test compiles and passes (GREEN state).
 *
 * <p>Story: E38S01 — DEC-22 § Red-first obligation.
 */
class InfoDtoModuleExistsTest {

    @Test
    void moduleSkeletonExists() {
        // Existence test: this test class being compilable and runnable is the
        // evidence that the vvwt-info-dto module skeleton is in place.
        // RED state: module directory and pom.xml do not exist.
        // GREEN state: module directory, pom.xml, and src structure created.
    }
}
