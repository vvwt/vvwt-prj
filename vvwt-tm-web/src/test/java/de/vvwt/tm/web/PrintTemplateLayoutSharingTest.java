// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
package de.vvwt.tm.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Structural guard for the print page templates under {@code templates/print/} — E70S07.
 *
 * <p>Pure JUnit 5 unit test: loads the Mustache templates as classpath resources and asserts that
 * the HTML document skeleton (DOCTYPE, {@code <html>}, {@code <head>}, {@code <body>}, stylesheet
 * link, print-header include, footer) is defined once in the shared parent layout {@code
 * print/print-layout.mustache} and not repeated in any print page template. No Spring context
 * required (DEC-44 exception for pure resource-reading unit tests).
 *
 * <p>Covers E70S07 AC2 / AC5. The rendered output of the pages is guarded by the existing print
 * integration tests (PrintControllerIT, PrintControllerCommentLeakIT, PrintControllerSliceTest,
 * LaufzettelPhase2EmptyRowsIT).
 *
 * @since E70S07
 */
@DisplayName("PrintTemplateLayoutSharingTest — E70S07 shared print document skeleton")
class PrintTemplateLayoutSharingTest {

    private static final String SHARED_LAYOUT_REFERENCE = "{{<print/print-layout}}";

    @ParameterizedTest(name = "print/{0}.mustache carries no own document skeleton")
    @ValueSource(
            strings = {
                "index",
                "error",
                "laufzettel",
                "laufzettel-all",
                "laufzettel-no-matches",
                "activity-schedule"
            })
    @DisplayName("AC2: print page template carries no own copy of the document skeleton")
    void pageTemplateCarriesNoOwnDocumentSkeleton(String pageName) throws IOException {
        String body = withoutSpdxHeader(loadTemplate("print/" + pageName));

        assertThat(body)
                .as("AC2: print/%s.mustache must not define its own document skeleton", pageName)
                .doesNotContain(
                        "<!DOCTYPE",
                        "<html",
                        "<head",
                        "<body",
                        "<link rel=\"stylesheet\"",
                        "{{> print-header}}",
                        "class=\"print-footer\"");
    }

    @ParameterizedTest(name = "print/{0}.mustache extends print/print-layout")
    @ValueSource(
            strings = {
                "index",
                "error",
                "laufzettel",
                "laufzettel-all",
                "laufzettel-no-matches",
                "activity-schedule"
            })
    @DisplayName("AC2: print page template extends the shared print layout")
    void pageTemplateExtendsSharedPrintLayout(String pageName) throws IOException {
        assertThat(loadTemplate("print/" + pageName))
                .as("AC2: print/%s.mustache must extend the shared print layout", pageName)
                .contains(SHARED_LAYOUT_REFERENCE);
    }

    @Test
    @DisplayName("AC2: shared print layout declares the page content block")
    void sharedPrintLayoutDeclaresContentBlock() throws IOException {
        assertThat(loadTemplate("print/print-layout"))
                .as("AC2: print/print-layout.mustache must declare the {{$content}} block")
                .contains("{{$content}}");
    }

    private static String loadTemplate(String name) throws IOException {
        String path = "/templates/" + name + ".mustache";
        try (InputStream is = PrintTemplateLayoutSharingTest.class.getResourceAsStream(path)) {
            assertThat(is).as("%s must be loadable from classpath", path).isNotNull();
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static String withoutSpdxHeader(String source) {
        return source.replaceFirst("(?s)^\\s*<!--.*?-->", "");
    }
}
