package de.vvwt.info.publish;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.vvwt.info.publish.internal.DefaultJcsCanonicalizer;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link JcsCanonicalizer} — RFC 8785 JCS canonical output.
 *
 * <p>DEC-22 Iron Law: tests written RED-first before production code. AC8 (E38S05): verifies
 * key-ordering and whitespace normalization.
 *
 * @see <a href="../../../../../../../../docs/governance/stories/E38S05.story.md">E38S05 AC8</a>
 */
class JcsCanonicalizerTest {

    private final JcsCanonicalizer canonicalizer = new DefaultJcsCanonicalizer();

    @Test
    void canonicalize_sortsObjectKeys() {
        // RFC 8785 requires keys to be sorted lexicographically
        String unordered = "{\"z\":1,\"a\":2}";
        byte[] canonical = canonicalizer.canonicalize(unordered);
        String result = new String(canonical, java.nio.charset.StandardCharsets.UTF_8);
        assertThat(result).isEqualTo("{\"a\":2,\"z\":1}");
    }

    @Test
    void canonicalize_removesWhitespace() {
        String pretty = "{ \"key\" : \"value\" }";
        byte[] canonical = canonicalizer.canonicalize(pretty);
        String result = new String(canonical, java.nio.charset.StandardCharsets.UTF_8);
        assertThat(result).isEqualTo("{\"key\":\"value\"}");
    }

    @Test
    void canonicalize_isIdempotent() {
        String json = "{\"b\":2,\"a\":1}";
        byte[] first = canonicalizer.canonicalize(json);
        byte[] second =
                canonicalizer.canonicalize(
                        new String(first, java.nio.charset.StandardCharsets.UTF_8));
        assertThat(first).isEqualTo(second);
    }

    @Test
    void canonicalize_producesUtf8Bytes() {
        String json = "{\"key\":\"value\"}";
        byte[] canonical = canonicalizer.canonicalize(json);
        assertThat(canonical).isNotNull();
        assertThat(canonical.length).isGreaterThan(0);
    }

    @Test
    void canonicalize_nullJson_throwsIllegalArgumentException() {
        assertThatThrownBy(() -> canonicalizer.canonicalize(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("null");
    }

    @Test
    void canonicalize_invalidJson_throwsIllegalArgumentException() {
        assertThatThrownBy(() -> canonicalizer.canonicalize("not-json"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void canonicalize_nestedObject_sortsAllLevels() {
        String json = "{\"outer\":{\"z\":3,\"a\":1},\"b\":2}";
        byte[] canonical = canonicalizer.canonicalize(json);
        String result = new String(canonical, java.nio.charset.StandardCharsets.UTF_8);
        // outer keys sorted, inner keys sorted
        assertThat(result).isEqualTo("{\"b\":2,\"outer\":{\"a\":1,\"z\":3}}");
    }
}
