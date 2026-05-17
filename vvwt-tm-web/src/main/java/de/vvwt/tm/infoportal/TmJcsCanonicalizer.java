// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
package de.vvwt.tm.infoportal;

/**
 * TM-side RFC 8785 JCS canonical JSON canonicalizer for publisher request signing.
 *
 * <p>Wraps {@link org.erdtman.jcs.JsonCanonicalizer} — same library version pin as {@code
 * vvwt-info-server}'s {@code JcsCanonicalizer} ({@code
 * io.github.erdtman:java-json-canonicalization:1.1}). Cross-implementation byte-stability is
 * guaranteed: the same JSON input produces byte-identical canonical output on both the TM publisher
 * side and the info-server verifier side.
 *
 * <p>Usage: sign {@code JCS(envelope_body_as_json)} and attach the Base64-encoded Ed25519 signature
 * as the {@code X-Vvwt-Signature} header.
 *
 * <p>DEC-58 Clause A + DEC-72 Clause A-ext: every self-created Spring component — including
 * {@code @Bean}-factory-produced first-party service beans — must have a public interface in the
 * bounded-context root package.
 *
 * @see de.vvwt.tm.infoportal.internal.DefaultTmJcsCanonicalizer
 * @since E57S05 (DEC-58/DEC-72 interface extraction)
 */
public interface TmJcsCanonicalizer {

    /**
     * Produces RFC 8785 JCS canonical bytes from the given JSON string.
     *
     * @param json a syntactically valid JSON string (object or array)
     * @return the JCS-canonical UTF-8 bytes
     * @throws IllegalArgumentException if {@code json} is null or syntactically invalid
     */
    byte[] canonicalize(String json);
}
