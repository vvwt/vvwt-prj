// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
package de.vvwt.info.publish.internal;

import de.vvwt.info.publish.JcsCanonicalizer;
import java.io.IOException;
import org.erdtman.jcs.JsonCanonicalizer;

/**
 * Wraps {@link org.erdtman.jcs.JsonCanonicalizer} to produce RFC 8785 JCS canonical bytes from a
 * JSON string (AC8 / Brief D-X6 a).
 *
 * <p>Both this server (E38S05) and the TM publisher (E38S09) MUST use the same library version pin
 * ({@code io.github.erdtman:java-json-canonicalization:1.1}) to guarantee byte-identical JCS
 * output. Cross-implementation byte-stability is verified by {@code JcsCrossImplByteStabilityIT}.
 *
 * <p>The canonical bytes are the input to signature verification: the publisher signs {@code
 * JCS(envelope_body_as_json)} and the server verifies the signature over the same canonical bytes
 * derived from the received payload. This defends against whitespace and key-ordering variations in
 * the wire representation.
 *
 * @see <a href="../../../../../../../../docs/governance/stories/E38S05.story.md">E38S05 AC8</a>
 * @see <a href="../../../../../../../../docs/governance/decisions/DEC-43.md">DEC-43 D4</a>
 */
public class DefaultJcsCanonicalizer implements JcsCanonicalizer {

    /**
     * Produces RFC 8785 JCS canonical bytes from the given JSON string.
     *
     * @param json a syntactically valid JSON string (object or array)
     * @return the JCS-canonical UTF-8 bytes
     * @throws IllegalArgumentException if {@code json} is null or syntactically invalid
     */
    @Override
    public byte[] canonicalize(String json) {
        if (json == null) {
            throw new IllegalArgumentException("json must not be null");
        }
        try {
            return new JsonCanonicalizer(json).getEncodedUTF8();
        } catch (IOException e) {
            throw new IllegalArgumentException("Failed to canonicalize JSON: " + e.getMessage(), e);
        }
    }
}
