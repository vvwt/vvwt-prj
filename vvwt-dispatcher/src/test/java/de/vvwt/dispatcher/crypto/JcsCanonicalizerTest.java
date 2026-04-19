package de.vvwt.dispatcher.crypto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link JcsCanonicalizer} (RFC 8785 JCS).
 *
 * <p>Test vectors are derived from the RFC 8785 specification: sorted object keys, compact output,
 * no whitespace, UTF-8.
 */
class JcsCanonicalizerTest {

    // -------------------------------------------------------------------------
    // Key sorting
    // -------------------------------------------------------------------------

    @Test
    void sortsObjectKeysLexicographically() throws IOException {
        String input = """
                {"z": 1, "a": 2, "m": 3}
                """.trim();
        String canonical = JcsCanonicalizer.canonicalizeToString(input);
        assertThat(canonical).isEqualTo("{\"a\":2,\"m\":3,\"z\":1}");
    }

    @Test
    void sortsNestedObjectKeysRecursively() throws IOException {
        String input =
                """
                {"outer_z": {"inner_z": 1, "inner_a": 2}, "outer_a": 3}
                """
                        .trim();
        String canonical = JcsCanonicalizer.canonicalizeToString(input);
        assertThat(canonical)
                .isEqualTo("{\"outer_a\":3,\"outer_z\":{\"inner_a\":2,\"inner_z\":1}}");
    }

    @Test
    void preservesArrayElementOrder() throws IOException {
        // RFC 8785 §3.2.3: array element order is preserved
        String input = """
                {"rows": [3, 1, 2]}
                """.trim();
        String canonical = JcsCanonicalizer.canonicalizeToString(input);
        assertThat(canonical).isEqualTo("{\"rows\":[3,1,2]}");
    }

    @Test
    void sortsKeysInObjectsInsideArrays() throws IOException {
        String input =
                """
                {"rows": [{"z": 1, "a": 2}, {"z": 3, "a": 4}]}
                """
                        .trim();
        String canonical = JcsCanonicalizer.canonicalizeToString(input);
        assertThat(canonical).isEqualTo("{\"rows\":[{\"a\":2,\"z\":1},{\"a\":4,\"z\":3}]}");
    }

    // -------------------------------------------------------------------------
    // phaseDef shape (representative of actual AC6 input)
    // -------------------------------------------------------------------------

    @Test
    void canonicalizesPhaseDefShape() throws IOException {
        // Whitespace variant — should produce compact form
        String input =
                """
                {
                  "phaseId": 1,
                  "rowCount": 2,
                  "rows": [
                    {"positions": [{"group": 0, "pos": 1}, {"group": 0, "pos": 0}]},
                    {"positions": [{"group": 1, "pos": 0}, {"group": 0, "pos": 2}]}
                  ]
                }
                """
                        .trim();
        String canonical = JcsCanonicalizer.canonicalizeToString(input);
        // Verify compact + key-sorted
        assertThat(canonical).doesNotContain(" ");
        assertThat(canonical).doesNotContain("\n");
        // phaseId before rowCount before rows (lex order)
        assertThat(canonical.indexOf("\"phaseId\"")).isLessThan(canonical.indexOf("\"rowCount\""));
        assertThat(canonical.indexOf("\"rowCount\"")).isLessThan(canonical.indexOf("\"rows\""));
        // within positions: group before pos
        assertThat(canonical.indexOf("\"group\"")).isLessThan(canonical.indexOf("\"pos\""));
    }

    // -------------------------------------------------------------------------
    // Determinism
    // -------------------------------------------------------------------------

    @Test
    void isDeterministicAcrossMultipleCalls() throws IOException {
        String input = "{\"z\": 99, \"a\": 1, \"m\": 42}";
        byte[] first = JcsCanonicalizer.canonicalize(input);
        byte[] second = JcsCanonicalizer.canonicalize(input);
        assertThat(first).isEqualTo(second);
    }

    @Test
    void sameSemanticContentDifferentFormatsProduceIdenticalBytes() throws IOException {
        String compact = "{\"z\":1,\"a\":2}";
        String pretty = "{\n  \"z\": 1,\n  \"a\": 2\n}";
        assertThat(JcsCanonicalizer.canonicalize(compact))
                .isEqualTo(JcsCanonicalizer.canonicalize(pretty));
    }

    // -------------------------------------------------------------------------
    // Output encoding
    // -------------------------------------------------------------------------

    @Test
    void outputIsUtf8Encoded() throws IOException {
        String input = "{\"key\": \"value\"}";
        byte[] canonical = JcsCanonicalizer.canonicalize(input);
        assertThat(new String(canonical, StandardCharsets.UTF_8)).isEqualTo("{\"key\":\"value\"}");
    }

    // -------------------------------------------------------------------------
    // Error handling
    // -------------------------------------------------------------------------

    @Test
    void rejectsNullInput() {
        assertThatThrownBy(() -> JcsCanonicalizer.canonicalize(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must not be null");
    }

    @Test
    void rejectsInvalidJson() {
        assertThatThrownBy(() -> JcsCanonicalizer.canonicalize("{not valid json}"))
                .isInstanceOf(java.io.IOException.class);
    }
}
