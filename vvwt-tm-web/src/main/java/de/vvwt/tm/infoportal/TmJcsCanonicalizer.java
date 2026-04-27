package de.vvwt.tm.infoportal;

import java.io.IOException;
import org.erdtman.jcs.JsonCanonicalizer;

/**
 * TM-side RFC 8785 JCS canonical JSON canonicalizer for publisher request signing (AC9, D-X6 a).
 *
 * <p>Wraps {@link org.erdtman.jcs.JsonCanonicalizer} — same library version pin as
 * {@code vvwt-info-server}'s {@code JcsCanonicalizer} ({@code io.github.erdtman:java-json-canonicalization:1.1}).
 * Cross-implementation byte-stability is guaranteed: the same JSON input produces byte-identical
 * canonical output on both the TM publisher side (this class) and the info-server verifier side.
 *
 * <p>Usage: sign {@code JCS(envelope_body_as_json)} and attach the Base64-encoded Ed25519
 * signature as the {@code X-Vvwt-Signature} header.
 *
 * @see <a href="../../../../../../../../.gaai/project/contexts/artefacts/stories/E38S09.story.md">E38S09 AC9</a>
 * @see <a href="../../../../../../../../.gaai/project/contexts/memory/decisions/DEC-43.md">DEC-43 D4</a>
 */
public class TmJcsCanonicalizer {

    /**
     * Produces RFC 8785 JCS canonical bytes from the given JSON string.
     *
     * @param json a syntactically valid JSON string (object or array)
     * @return the JCS-canonical UTF-8 bytes
     * @throws IllegalArgumentException if {@code json} is null or syntactically invalid
     */
    public byte[] canonicalize(String json) {
        if (json == null) {
            throw new IllegalArgumentException("json must not be null");
        }
        try {
            return new JsonCanonicalizer(json).getEncodedUTF8();
        } catch (IOException e) {
            throw new IllegalArgumentException(
                    "Failed to canonicalize JSON: " + e.getMessage(), e);
        }
    }
}
