// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
package de.vvwt.info.publish;

/**
 * Wraps RFC 8785 JCS canonical byte production from a JSON string (AC8 / Brief D-X6 a).
 *
 * @see de.vvwt.info.publish.internal.DefaultJcsCanonicalizer
 * @see <a href="../../../../../../../docs/governance/stories/E38S05.story.md">E38S05 AC8</a>
 * @see <a href="../../../../../../../docs/governance/decisions/DEC-43.md">DEC-43 D4</a>
 */
public interface JcsCanonicalizer {

    /**
     * Produces RFC 8785 JCS canonical bytes from the given JSON string.
     *
     * @param json a syntactically valid JSON string (object or array)
     * @return the JCS-canonical UTF-8 bytes
     * @throws IllegalArgumentException if {@code json} is null or syntactically invalid
     */
    byte[] canonicalize(String json);
}
