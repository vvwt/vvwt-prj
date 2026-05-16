// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
package de.vvwt.info.build;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * AC9 (security) — Durable Maven Enforcer negative-test IT.
 *
 * <p>This IT programmatically invokes Maven's Enforcer plugin against a fixture pom that declares a
 * forbidden dependency on {@code vvwt-tm-web}. It asserts:
 *
 * <ol>
 *   <li>Enforcer exit code is non-zero (build fails)
 *   <li>Error message substring contains {@code bannedDependencies}
 * </ol>
 *
 * <p>This test is PERMANENT — it must not be removed before commit. It provides durable evidence
 * that the Enforcer rule fires for QA review and regression protection. DEC-42 D2 enforcement.
 * Story: E38S01.
 */
class BannedDependenciesEnforcerIT {

    private static final String FIXTURE_POM =
            """
            <?xml version="1.0" encoding="UTF-8"?>
            <project xmlns="http://maven.apache.org/POM/4.0.0"
                     xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                     xsi:schemaLocation="http://maven.apache.org/POM/4.0.0
                         https://maven.apache.org/xsd/maven-4.0.0.xsd">
              <modelVersion>4.0.0</modelVersion>
              <groupId>de.vvwt.test</groupId>
              <artifactId>enforcer-test-fixture</artifactId>
              <version>1.0-SNAPSHOT</version>
              <packaging>jar</packaging>

              <dependencies>
                <!-- FORBIDDEN: vvwt-info-server MUST NOT depend on vvwt-tm-web (DEC-42 D2) -->
                <dependency>
                  <groupId>de.vvwt</groupId>
                  <artifactId>vvwt-tm-web</artifactId>
                  <version>1.0.0-SNAPSHOT</version>
                </dependency>
              </dependencies>

              <build>
                <plugins>
                  <plugin>
                    <groupId>org.apache.maven.plugins</groupId>
                    <artifactId>maven-enforcer-plugin</artifactId>
                    <version>3.5.0</version>
                    <executions>
                      <execution>
                        <id>enforce-banned-deps</id>
                        <goals><goal>enforce</goal></goals>
                        <configuration>
                          <rules>
                            <bannedDependencies>
                              <excludes>
                                <exclude>de.vvwt:vvwt-tm-web</exclude>
                                <exclude>de.vvwt:vvwt-tm-*</exclude>
                              </excludes>
                              <message>DEC-42 D2: vvwt-info-server and vvwt-info-client MUST NOT depend on TM modules</message>
                            </bannedDependencies>
                          </rules>
                          <fail>true</fail>
                        </configuration>
                      </execution>
                    </executions>
                  </plugin>
                </plugins>
              </build>
            </project>
            """;

    @Test
    void enforcerRejectsFixturePomWithForbiddenDependency(@TempDir Path tempDir) throws Exception {
        // Step 1: write fixture pom to temp dir
        Path fixturePom = tempDir.resolve("pom.xml");
        Files.writeString(fixturePom, FIXTURE_POM, StandardCharsets.UTF_8);

        // Step 2: invoke Maven 'validate' phase against the fixture pom.
        // The Enforcer plugin execution is bound to the default lifecycle (no explicit phase
        // binding means it runs at the 'validate' phase by default). Running the 'validate'
        // phase triggers the bound execution and fires the bannedDependencies rule check.
        // NOTE: invoking 'enforcer:enforce' directly from CLI bypasses lifecycle bindings
        // and finds no configured rules; 'validate' correctly exercises the bound execution.
        String mvnCommand = resolveMvnExecutable();
        ProcessBuilder pb =
                new ProcessBuilder(
                                mvnCommand,
                                "--batch-mode",
                                "--no-transfer-progress",
                                "validate",
                                "-f",
                                fixturePom.toAbsolutePath().toString())
                        .redirectErrorStream(true);

        Process process = pb.start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        int exitCode = process.waitFor();

        // AC9(a): Enforcer exit code must be non-zero
        assertThat(exitCode)
                .as(
                        "Maven Enforcer should fail (non-zero exit) for a pom with banned"
                                + " dependency on vvwt-tm-web. Output:\n"
                                + "%s",
                        output)
                .isNotZero();

        // AC9(b): error message must contain 'bannedDependencies'
        assertThat(output)
                .as(
                        "Maven Enforcer output should contain 'bannedDependencies' rule name."
                                + " Full output:\n%s",
                        output)
                .containsIgnoringCase("bannedDependencies");
    }

    private static String resolveMvnExecutable() {
        // Resolve mvn from MAVEN_HOME, M2_HOME, or PATH
        String mavenHome = System.getenv("MAVEN_HOME");
        if (mavenHome == null) {
            mavenHome = System.getenv("M2_HOME");
        }
        if (mavenHome != null) {
            File mvnBin = new File(mavenHome, "bin/mvn");
            if (mvnBin.exists()) {
                return mvnBin.getAbsolutePath();
            }
        }
        // Fall back to "mvn" on PATH
        return "mvn";
    }
}
