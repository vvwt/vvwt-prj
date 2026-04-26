package de.vvwt.slotopt.dispatcher.crypto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link JcsCanonicalizer} per RFC 8785 (JSON Canonicalization Scheme).
 *
 * <p>RED-first per DEC-22 / AC-JCS-CANONICALIZER: tests written before the production class is
 * created.
 *
 * <p>Test vectors sourced from RFC 8785 §B (normative examples).
 *
 * @SpecSource RFC 8785 — JSON Canonicalization Scheme, §B (IETF, 2021)
 *     https://www.rfc-editor.org/rfc/rfc8785#appendix-B
 */
class JcsCanonicalizerTest {

    private ObjectMapper mapper;
    private JcsCanonicalizer canonicalizer;

    @BeforeEach
    void setUp() {
        mapper = new ObjectMapper();
        canonicalizer = new JcsCanonicalizer();
    }

    /**
     * RFC 8785 §B.1 — empty object canonical form is {@code {}}.
     *
     * @SpecSource RFC 8785 §B.1
     */
    @Test
    void emptyObject_canonicalizesToEmptyObject() throws IOException {
        var node = mapper.readTree("{}");
        var result = new String(canonicalizer.canonicalize(node), StandardCharsets.UTF_8);
        assertThat(result).isEqualTo("{}");
    }

    /**
     * RFC 8785 §3.2.3 — keys MUST be sorted lexicographically by their Unicode code-point values
     * (equivalent to Java {@link String#compareTo} for strings in the Basic Multilingual Plane).
     *
     * @SpecSource RFC 8785 §3.2.3
     */
    @Test
    void keysAreSortedLexicographically() throws IOException {
        var node = mapper.readTree("{\"z\": 1, \"a\": 2, \"m\": 3}");
        var result = new String(canonicalizer.canonicalize(node), StandardCharsets.UTF_8);
        assertThat(result).isEqualTo("{\"a\":2,\"m\":3,\"z\":1}");
    }

    /**
     * RFC 8785 — no whitespace (no spaces, no newlines) in canonical form.
     *
     * @SpecSource RFC 8785 §3.2 (serialization rules — no unnecessary whitespace)
     */
    @Test
    void noWhitespaceInOutput() throws IOException {
        var node = mapper.readTree("{\"key\": \"value\"}");
        var result = new String(canonicalizer.canonicalize(node), StandardCharsets.UTF_8);
        assertThat(result).doesNotContain(" ", "\n", "\t", "\r");
    }

    /**
     * Nested object keys are also sorted recursively.
     *
     * @SpecSource RFC 8785 §3.2.3 (applied recursively to nested structures)
     */
    @Test
    void nestedObjectKeysAreSortedRecursively() throws IOException {
        var node = mapper.readTree("{\"z\": {\"y\": 1, \"b\": 2}, \"a\": 3}");
        var result = new String(canonicalizer.canonicalize(node), StandardCharsets.UTF_8);
        assertThat(result).isEqualTo("{\"a\":3,\"z\":{\"b\":2,\"y\":1}}");
    }

    /**
     * Array order is preserved (RFC 8785 §3.2.2 — arrays maintain their element order).
     *
     * @SpecSource RFC 8785 §3.2.2
     */
    @Test
    void arrayOrderIsPreserved() throws IOException {
        var node = mapper.readTree("{\"items\": [3, 1, 2]}");
        var result = new String(canonicalizer.canonicalize(node), StandardCharsets.UTF_8);
        assertThat(result).isEqualTo("{\"items\":[3,1,2]}");
    }

    /**
     * Output encoding is UTF-8 (RFC 8785 §3.1).
     *
     * @SpecSource RFC 8785 §3.1
     */
    @Test
    void outputIsUtf8() throws IOException {
        var node = mapper.readTree("{\"key\": \"value\"}");
        var bytes = canonicalizer.canonicalize(node);
        // Validate that decoding as UTF-8 produces the expected string without exceptions.
        var result = new String(bytes, StandardCharsets.UTF_8);
        assertThat(result).isEqualTo("{\"key\":\"value\"}");
    }

    /**
     * Null JsonNode argument throws NullPointerException (fail-fast contract).
     */
    @Test
    void nullNode_throwsNullPointerException() {
        assertThatThrownBy(() -> canonicalizer.canonicalize(null))
                .isInstanceOf(NullPointerException.class);
    }
}
