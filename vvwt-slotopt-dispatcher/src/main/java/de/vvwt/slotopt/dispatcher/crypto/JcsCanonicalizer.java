package de.vvwt.slotopt.dispatcher.crypto;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Public interface for RFC 8785 (JSON Canonicalization Scheme — JCS) canonicalization.
 *
 * <p>Consumers reference this interface; the implementation is {@link
 * de.vvwt.slotopt.dispatcher.crypto.internal.DefaultJcsCanonicalizer} in the {@code
 * crypto.internal} package (DEC-35, DEC-58, DEC-72).
 *
 * <p>JCS rules (normative, per RFC 8785):
 *
 * <ul>
 *   <li>Object keys are sorted lexicographically by Unicode code-point value (§3.2.3).
 *   <li>No whitespace outside string values (§3.2).
 *   <li>Array element order is preserved (§3.2.2).
 *   <li>Output encoding is UTF-8 (§3.1).
 *   <li>Number serialization follows ES6 Number-to-String rules (§3.2.2.3); for integers this is
 *       equivalent to the usual decimal representation.
 * </ul>
 *
 * <p>Spec: E37S04 AC-JCS-CANONICALIZER; E37S02 spec section (a) §JSON Canonicalization; RFC 8785 —
 * JSON Canonicalization Scheme (IETF, 2021). @SpecSource RFC 8785
 * https://www.rfc-editor.org/rfc/rfc8785
 *
 * <p>Story: E57S03 — DEC-58/DEC-72 interface-mandate compliance.
 */
public interface JcsCanonicalizer {

    /**
     * Produces the RFC 8785 canonical UTF-8 byte representation of {@code node}.
     *
     * @param node the JSON node to canonicalize; must not be {@code null}
     * @return the canonical UTF-8 bytes
     * @throws NullPointerException if {@code node} is {@code null}
     * @throws RuntimeException wrapping any {@link java.io.IOException} from the serialization
     *     process
     */
    byte[] canonicalize(JsonNode node);
}
