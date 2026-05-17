// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
package de.vvwt.tm.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import de.vvwt.tm.tenant.internal.TmDataDirProperties;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * AC-TEST-REGRESSION-GUARD (E16S05): regression guard proving that the Tournament Manager test
 * profile places its temporary artefacts under the Maven module build directory ({@code target/}),
 * not under the shared OS temp directory (the default {@code java.io.tmpdir}, e.g. {@code /tmp}).
 *
 * <p><b>Root cause guarded.</b> {@code application-test.yml} sets {@code
 * tm.data.dir=${java.io.tmpdir}/vvwt-tm-test-${random.uuid}} — a fresh per-{@code
 * ApplicationContext} tenant data root that nothing ever deletes (it is an application data root,
 * not a JUnit {@code @TempDir}). Before E16S05 the test JVM ran with the OS default {@code
 * java.io.tmpdir}, so every {@code mvn verify} left orphan {@code vvwt-tm-test-*} (and embedded
 * Tomcat {@code tomcat.*}) directories in {@code /tmp}, accumulating without bound until the host
 * disk was exhausted. E16S05 retargets the surefire/failsafe test JVMs' {@code java.io.tmpdir} into
 * {@code ${project.build.directory}/test-tmp} (parent {@code pom.xml} {@code pluginManagement}), so
 * {@code mvn clean} reclaims everything.
 *
 * <p><b>RED/GREEN behaviour.</b> This guard is RED on the pre-fix configuration — with the OS
 * default {@code java.io.tmpdir}, {@code tm.data.dir} resolves under {@code /tmp} (or the
 * platform-equivalent OS temp directory), failing both assertions below. It is GREEN once the
 * surefire/failsafe {@code java.io.tmpdir} retargeting is in place. Per DEC-22 / DEC-67 the E16S05
 * fix is a build-descriptor change authoring no first-party production code, so this guard is
 * authored against the post-fix expectation; its RED-on-pre-fix behaviour was verified empirically
 * during delivery (impl-report).
 *
 * <p>Loads the application context under the {@code test} profile ({@code @ActiveProfiles("test")},
 * so {@code application-test.yml} — including the {@code tm.data.dir} override under test — takes
 * effect) with an in-memory H2 datasource. Uses {@code SpringBootTest.WebEnvironment.MOCK} — this
 * is an infrastructure-configuration IT, not a controller IT (cf. {@link DataDirPathDerivationIT}).
 */
@SpringBootTest(
        properties = {
            "spring.datasource.url=jdbc:h2:mem:e16s05-test-tmp-guard-it;DB_CLOSE_DELAY=-1",
            "spring.flyway.enabled=false",
        })
@ActiveProfiles("test")
class TestTempDirIsBuildScopedIT {

    /** The Maven build-directory name — the directory tree the fix relocates test temp into. */
    private static final String BUILD_DIR_NAME = "target";

    /**
     * OS default temp roots that the pre-fix configuration leaked into. {@code tm.data.dir} must
     * resolve under none of them once the fix is in place.
     */
    private static final String[] OS_DEFAULT_TEMP_ROOTS = {"/tmp", "/var/tmp", "/var/folders"};

    @Autowired private TmDataDirProperties tmDataDirProperties;

    @Test
    void tmDataDirResolvesUnderTheModuleBuildDirectory() {
        Path resolved = Paths.get(tmDataDirProperties.getDir()).toAbsolutePath().normalize();

        boolean underBuildDir = false;
        for (Path segment : resolved) {
            if (BUILD_DIR_NAME.equals(segment.toString())) {
                underBuildDir = true;
                break;
            }
        }

        assertThat(underBuildDir)
                .as(
                        "tm.data.dir (test profile) must resolve under the Maven module build"
                                + " directory (a '%s' path segment) so that `mvn clean` reclaims"
                                + " test temp artefacts — resolved value was: %s",
                        BUILD_DIR_NAME, resolved)
                .isTrue();
    }

    @Test
    void tmDataDirDoesNotResolveUnderTheSharedOsTempDirectory() {
        Path resolved = Paths.get(tmDataDirProperties.getDir()).toAbsolutePath().normalize();

        for (String osTempRoot : OS_DEFAULT_TEMP_ROOTS) {
            assertThat(resolved.startsWith(Paths.get(osTempRoot)))
                    .as(
                            "tm.data.dir (test profile) must NOT resolve under the shared OS temp"
                                + " directory '%s' — orphan directories there accumulate without"
                                + " bound and exhaust the host disk (E16S05). Resolved value was:"
                                + " %s",
                            osTempRoot, resolved)
                    .isFalse();
        }
    }
}
