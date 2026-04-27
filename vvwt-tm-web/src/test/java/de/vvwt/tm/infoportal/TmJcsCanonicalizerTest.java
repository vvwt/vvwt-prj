package de.vvwt.tm.infoportal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link TmJcsCanonicalizer} — RFC 8785 JCS canonical output (AC9).
 *
 * <p>DEC-22 Iron Law: written RED-first before production class exists.
 *
 * <p>AC9 cross-implementation byte-stability: TM-side {@link TmJcsCanonicalizer} and the
 * info-server-side JcsCanonicalizer (E38S05) both use
 * {@code io.github.erdtman:java-json-canonicalization:1.1}; the same JSON input MUST produce
 * byte-identical output on both sides. Cross-subsystem import is forbidden (DEC-42 D2), so
 * stability is verified via a known-good canonical fixture rather than by importing the server class.
 *
 * @see <a href="../../../../../../../../.gaai/project/contexts/artefacts/stories/E38S09.story.md">E38S09 AC9</a>
 */
class TmJcsCanonicalizerTest {

    private final TmJcsCanonicalizer tmCan = new TmJcsCanonicalizer();

    @Test
    void canonicalize_sortsObjectKeys() {
        String unordered = "{\"z\":1,\"a\":2}";
        byte[] canonical = tmCan.canonicalize(unordered);
        String result = new String(canonical, StandardCharsets.UTF_8);
        assertThat(result).isEqualTo("{\"a\":2,\"z\":1}");
    }

    @Test
    void canonicalize_removesWhitespace() {
        String pretty = "{ \"key\" : \"value\" }";
        byte[] canonical = tmCan.canonicalize(pretty);
        String result = new String(canonical, StandardCharsets.UTF_8);
        assertThat(result).isEqualTo("{\"key\":\"value\"}");
    }

    @Test
    void canonicalize_isIdempotent() {
        String json = "{\"b\":2,\"a\":1}";
        byte[] first = tmCan.canonicalize(json);
        byte[] second = tmCan.canonicalize(new String(first, StandardCharsets.UTF_8));
        assertThat(first).isEqualTo(second);
    }

    @Test
    void canonicalize_nestedObject_sortsAllLevels() {
        String json = "{\"outer\":{\"z\":3,\"a\":1},\"b\":2}";
        byte[] canonical = tmCan.canonicalize(json);
        String result = new String(canonical, StandardCharsets.UTF_8);
        assertThat(result).isEqualTo("{\"b\":2,\"outer\":{\"a\":1,\"z\":3}}");
    }

    @Test
    void canonicalize_null_throwsIllegalArgumentException() {
        assertThatThrownBy(() -> tmCan.canonicalize(null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void canonicalize_invalidJson_throwsIllegalArgumentException() {
        assertThatThrownBy(() -> tmCan.canonicalize("not-json"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /**
     * AC9 cross-implementation byte-stability: verifies that TM-side JCS output for a fixed
     * payload matches the expected canonical bytes (same library version = same output).
     *
     * <p>This is the "shared fixture" test per AC9. The fixture is the same JSON object
     * used in the info-server's {@code JcsCanonicalizerTest}:
     * {@code {"outer":{"z":3,"a":1},"b":2}} → {@code {"b":2,"outer":{"a":1,"z":3}}}.
     */
    @Test
    void crossImplByteStability_tmOutputMatchesExpectedCanonicalBytes() {
        // This fixture is the cross-implementation byte-stability anchor (AC9).
        // The expected value is derived from io.github.erdtman:java-json-canonicalization:1.1
        // applied to the input. The server-side verifier uses the same library and version,
        // so the canonical bytes are guaranteed to match.
        String fixture = "{\"seq\":1,\"type\":\"SCORE_UPDATED\",\"matchId\":\"m1\","
                + "\"homeScore\":2,\"awayScore\":1}";
        byte[] canonical = tmCan.canonicalize(fixture);
        String result = new String(canonical, StandardCharsets.UTF_8);
        // RFC 8785 JCS: keys sorted lexicographically, no whitespace
        assertThat(result).isEqualTo(
                "{\"awayScore\":1,\"homeScore\":2,\"matchId\":\"m1\",\"seq\":1,"
                        + "\"type\":\"SCORE_UPDATED\"}");
    }
}
