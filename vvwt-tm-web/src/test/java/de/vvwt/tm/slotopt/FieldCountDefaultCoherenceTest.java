package de.vvwt.tm.slotopt;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * RED-first regression test for E51S16 — {@code AC-TEST-DEFAULT-FIELDCOUNT-COHERENCE-RED}.
 *
 * <p>Verifies that all {@code @Value("${tm.slotopt.fallback.field-count:N}")} declaration sites in
 * the {@code vvwt-tm-web} module use the SAME default value {@code N=3}. This test is a drift
 * guard: if any site declares a different default, the test fails loudly.
 *
 * <h2>Declaration sites (5 sites — AC-IMPL-DEFAULT-FIELDCOUNT-COHERENCE)</h2>
 *
 * <ol>
 *   <li>{@code de.vvwt.tm.slotopt.PhaseToRawPhaseDefMapper} — pre-existing, {@code :3}
 *   <li>{@code de.vvwt.tm.slotopt.FallbackSlotOptimizationClient} — pre-existing, {@code :3}
 *   <li>{@code de.vvwt.tm.tournament.internal.MatchGenJobExecutor} — NEW (E51S16 B-b1), {@code :3}
 *   <li>{@code de.vvwt.tm.tournament.internal.DefaultRoundAssignmentService} — NEW (E51S16 B-b1),
 *       {@code :3} (coherence anchor field)
 *   <li>{@code src/main/resources/application.yml} — value: {@code ${TM_SLOTOPT_FIELDCOUNT:3}} (the
 *       resolved value must be 3 when env-var is absent)
 * </ol>
 *
 * <h2>DEC-22 RED-first</h2>
 *
 * <p>Before E51S16 adds the {@code @Value} sites in {@code MatchGenJobExecutor} and {@code
 * DefaultRoundAssignmentService}, those files do not contain the literal
 * {@code @Value("${tm.slotopt.fallback.field-count:3}")} — causing this test to fail (RED). After
 * E51S16's B-b1 refactor, all 4 Java sites contain the literal and the test goes GREEN.
 *
 * @see <a href="DEC-22">DEC-22 — TDD Iron Law (RED-first)</a>
 * @see <a href="AC-IMPL-DEFAULT-FIELDCOUNT-COHERENCE">AC-IMPL-DEFAULT-FIELDCOUNT-COHERENCE</a>
 * @see <a href="AC-TEST-DEFAULT-FIELDCOUNT-COHERENCE-RED">AC-TEST-DEFAULT-FIELDCOUNT-COHERENCE-RED
 *     </a>
 * @see <a href="E51S16">E51S16 — B-b1 Modulith-cycle elimination</a>
 */
@DisplayName("FieldCountDefaultCoherenceTest — E51S16 AC-TEST-DEFAULT-FIELDCOUNT-COHERENCE-RED")
class FieldCountDefaultCoherenceTest {

    /**
     * The canonical {@code @Value} literal that EVERY Java declaration site MUST use.
     *
     * <p>This constant is a test-only anchor and does NOT participate in the 5 production sites
     * being validated.
     */
    private static final String EXPECTED_VALUE_LITERAL =
            "@Value(\"${tm.slotopt.fallback.field-count:3}\")";

    /**
     * The expected default suffix inside the Spring EL expression. Both the Java {@code @Value}
     * sites (suffix {@code :3}) and the YAML site (suffix {@code :3} in {@code
     * ${TM_SLOTOPT_FIELDCOUNT:3}}) must carry {@code :3}.
     */
    private static final String EXPECTED_DEFAULT_SUFFIX = ":3}";

    // ── Paths to declaration sites (relative to module root) ─────────────────────────────────────

    private static final String MODULE_ROOT = "vvwt-tm-web/src/main/java/de/vvwt/tm";

    /** Site 1: PhaseToRawPhaseDefMapper — pre-existing slotopt site */
    private static final String SITE_MAPPER =
            MODULE_ROOT + "/slotopt/PhaseToRawPhaseDefMapper.java";

    /** Site 2: FallbackSlotOptimizationClient — pre-existing slotopt site */
    private static final String SITE_FALLBACK_CLIENT =
            MODULE_ROOT + "/slotopt/FallbackSlotOptimizationClient.java";

    /** Site 3: MatchGenJobExecutor — NEW B-b1 tournament site */
    private static final String SITE_MATCH_GEN_EXECUTOR =
            MODULE_ROOT + "/tournament/internal/MatchGenJobExecutor.java";

    /** Site 4: DefaultRoundAssignmentService — NEW B-b1 tournament coherence-anchor site */
    private static final String SITE_ROUND_ASSIGNMENT =
            MODULE_ROOT + "/tournament/internal/DefaultRoundAssignmentService.java";

    /** Site 5: application.yml — ${TM_SLOTOPT_FIELDCOUNT:3} must use :3 default */
    private static final String SITE_APPLICATION_YML =
            "vvwt-tm-web/src/main/resources/application.yml";

    // =========================================================================
    // AC-TEST-DEFAULT-FIELDCOUNT-COHERENCE-RED
    // =========================================================================

    /**
     * Verifies that all 4 Java {@code @Value} declaration sites for {@code
     * ${tm.slotopt.fallback.field-count}} use the literal default {@code :3}.
     *
     * <p>A site that uses {@code :5}, {@code :2}, or omits the default entirely will cause this
     * test to fail, alerting the developer of the drift.
     */
    @Test
    @DisplayName(
            "AC-TEST-DEFAULT-FIELDCOUNT-COHERENCE-RED:"
                    + " all @Value(\"${tm.slotopt.fallback.field-count:N}\")"
                    + " declaration sites use N=3 (drift guard)")
    void allJavaValueSites_containExpectedLiteral_withDefaultThree() throws IOException {
        String[] javaSites = {
            SITE_MAPPER, SITE_FALLBACK_CLIENT, SITE_MATCH_GEN_EXECUTOR, SITE_ROUND_ASSIGNMENT
        };
        String[] siteLabels = {
            "PhaseToRawPhaseDefMapper (site 1)",
            "FallbackSlotOptimizationClient (site 2)",
            "MatchGenJobExecutor (site 3)",
            "DefaultRoundAssignmentService (site 4)"
        };

        for (int i = 0; i < javaSites.length; i++) {
            Path filePath = resolveFromModuleRoot(javaSites[i]);
            String content = readFile(filePath);
            assertThat(content)
                    .as(
                            "AC-TEST-DEFAULT-FIELDCOUNT-COHERENCE-RED: %s (%s)"
                                    + " must contain the literal @Value annotation"
                                    + " [%s] — drift guard for B-b1 refactor",
                            siteLabels[i], filePath, EXPECTED_VALUE_LITERAL)
                    .contains(EXPECTED_VALUE_LITERAL);
        }
    }

    /**
     * Verifies that the YAML declaration site uses {@code :3} as the default for the env-var
     * expression {@code ${TM_SLOTOPT_FIELDCOUNT:3}}.
     *
     * <p>Ensures the configured default stays aligned with the Java {@code @Value} defaults.
     */
    @Test
    @DisplayName(
            "AC-TEST-DEFAULT-FIELDCOUNT-COHERENCE-RED:"
                    + " application.yml field-count uses :3 default in ${TM_SLOTOPT_FIELDCOUNT:3}")
    void applicationYml_fieldCountDefault_isThree() throws IOException {
        Path filePath = resolveFromModuleRoot(SITE_APPLICATION_YML);
        String content = readFile(filePath);
        // YAML site: field-count: ${TM_SLOTOPT_FIELDCOUNT:3}
        assertThat(content)
                .as(
                        "AC-TEST-DEFAULT-FIELDCOUNT-COHERENCE-RED: application.yml"
                                + " field-count entry must use ${TM_SLOTOPT_FIELDCOUNT:3}"
                                + " (%s)",
                        filePath)
                .contains("field-count: ${TM_SLOTOPT_FIELDCOUNT:3}");
    }

    // ── Helpers ──────────────────────────────────────────────────────────────────────────────────

    /**
     * Resolves a path relative to the Maven module root (the directory containing {@code
     * vvwt-tm-web/}).
     *
     * <p>Strategy: walk upward from the current working directory (Maven target/) or from a known
     * anchor in the class-path until the expected relative path is found. This allows the test to
     * run from any Maven working directory without hardcoded absolute paths.
     */
    private Path resolveFromModuleRoot(String relativePath) {
        // Maven sets user.dir to the module root (the pom.xml directory) during test execution.
        // Walk up from cwd until we find the file, or fail with a clear message.
        Path cwd = Paths.get(System.getProperty("user.dir"));

        // Try cwd directly (Maven module root = vvwt-tm-web/)
        Path candidate = cwd.resolve(relativePath);
        if (Files.exists(candidate)) {
            return candidate;
        }

        // Try one level up (in case cwd is a subdirectory during IDE runs)
        candidate = cwd.getParent().resolve(relativePath);
        if (Files.exists(candidate)) {
            return candidate;
        }

        // Attempt to strip 'vvwt-tm-web/' prefix if cwd IS the module dir already
        // (i.e., file is at src/main/java/... relative to module)
        String strippedRelative =
                relativePath.startsWith("vvwt-tm-web/")
                        ? relativePath.substring("vvwt-tm-web/".length())
                        : relativePath;
        candidate = cwd.resolve(strippedRelative);
        if (Files.exists(candidate)) {
            return candidate;
        }

        // Fail loudly if no path resolves
        throw new IllegalStateException(
                "FieldCountDefaultCoherenceTest: cannot resolve declaration-site path '"
                        + relativePath
                        + "' from cwd='"
                        + cwd
                        + "'. Run from Maven module root or project root.");
    }

    private String readFile(Path path) throws IOException {
        return Files.readString(path, StandardCharsets.UTF_8);
    }
}
