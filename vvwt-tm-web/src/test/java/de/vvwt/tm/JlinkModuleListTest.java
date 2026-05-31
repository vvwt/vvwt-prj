// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
package de.vvwt.tm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Static-source regression guard for the jlink {@code --add-modules} argument in {@code
 * vvwt-tm-web/pom.xml}.
 *
 * <p>AC2 (E12S10): A static-source assertion that reads {@code vvwt-tm-web/pom.xml} and asserts the
 * comma-separated value of the {@code <argument>} element following the {@code --add-modules}
 * argument in the {@code create-runtime-image} execution contains the literal token {@code
 * jdk.localedata}.
 *
 * <p>Without {@code jdk.localedata} in the linked runtime, the JDK ships only ROOT and en (US) CLDR
 * data — {@code DateTimeFormatter.ofLocalizedDate(FormatStyle.LONG).withLocale(Locale.GERMAN)}
 * silently falls back to the ROOT-locale format ("2026 May 30" instead of "30. Mai 2026").
 *
 * <p>This test does NOT launch a jlink image, exec jdeps/jlink, or require chromium — it is a
 * GREEN-only regression guard per DEC-67 Clause 2.
 *
 * @see <a
 *     href="../../../../../../../../../../.gaai/project/contexts/artefacts/stories/E12S10.story.md">Story
 *     E12S10</a>
 */
@DisplayName("E12S10 — jlink --add-modules guard: jdk.localedata must be present")
class JlinkModuleListTest {

    /** Pattern matching the comma-separated module list that follows {@code --add-modules}. */
    private static final Pattern ADD_MODULES_PATTERN =
            Pattern.compile(
                    "<argument>--add-modules</argument>\\s*<argument>([^<]+)</argument>",
                    Pattern.DOTALL);

    /**
     * AC2: reads {@code vvwt-tm-web/pom.xml} and asserts the {@code --add-modules} argument
     * contains {@code jdk.localedata}.
     */
    @Test
    @DisplayName(
            "AC2: --add-modules argument in create-runtime-image execution contains jdk.localedata")
    void jlinkAddModulesContainsLocaleData() throws IOException {
        Path pomPath = resolvePomPath();

        assertThat(pomPath)
                .as("vvwt-tm-web/pom.xml must be readable at " + pomPath)
                .exists()
                .isReadable();

        String pomContent = Files.readString(pomPath);

        Matcher matcher = ADD_MODULES_PATTERN.matcher(pomContent);

        if (!matcher.find()) {
            fail(
                    "Could not locate the --add-modules <argument> pair in "
                            + pomPath
                            + ". Expected pattern: <argument>--add-modules</argument> followed by"
                            + " <argument>{module-list}</argument>");
        }

        String moduleList = matcher.group(1).trim();
        List<String> modules = Arrays.asList(moduleList.split(","));

        assertThat(modules)
                .as(
                        "The jlink --add-modules argument in vvwt-tm-web/pom.xml must contain"
                                + " 'jdk.localedata'. Without it, the linked runtime ships only"
                                + " ROOT + en (US) CLDR data and Locale.GERMAN date formatting"
                                + " silently falls back to '2026 May 30' (E12S10 root cause)."
                                + " Current module list: "
                                + moduleList)
                .contains("jdk.localedata");
    }

    /**
     * Resolves the path to {@code vvwt-tm-web/pom.xml} relative to the Maven module directory.
     * Maven Surefire/Failsafe sets {@code user.dir} to the module directory during test execution,
     * so {@code user.dir/pom.xml} is the module's own pom.xml.
     */
    private static Path resolvePomPath() {
        // When run by Maven Surefire/Failsafe, user.dir = vvwt-tm-web/ module directory.
        return Paths.get(System.getProperty("user.dir"), "pom.xml");
    }
}
