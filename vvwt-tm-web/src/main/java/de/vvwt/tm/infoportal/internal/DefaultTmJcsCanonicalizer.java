// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
package de.vvwt.tm.infoportal.internal;

import de.vvwt.tm.infoportal.TmJcsCanonicalizer;
import java.io.IOException;
import org.erdtman.jcs.JsonCanonicalizer;

/**
 * Default implementation of {@link TmJcsCanonicalizer}: RFC 8785 JCS canonical JSON canonicalizer
 * for publisher request signing (AC9, D-X6 a).
 *
 * <p>Wraps {@link org.erdtman.jcs.JsonCanonicalizer} — same library version pin as {@code
 * vvwt-info-server}'s {@code JcsCanonicalizer}. Cross-implementation byte-stability is guaranteed:
 * the same JSON input produces byte-identical canonical output on both sides.
 *
 * <p>Bean registration is via {@code InfoPortalConfig#tmJcsCanonicalizer()} — this class carries no
 * {@code @Component} annotation (DEC-70: no test-only or duplicate wiring).
 *
 * @see TmJcsCanonicalizer
 * @see <a
 *     href="../../../../../../../../../.gaai/project/contexts/artefacts/stories/E38S09.story.md">
 *     E38S09 AC9</a>
 * @see <a
 *     href="../../../../../../../../../.gaai/project/contexts/memory/decisions/DEC-43.md">DEC-43
 *     D4</a>
 * @since E57S05 (DEC-58/DEC-72 interface extraction)
 */
public class DefaultTmJcsCanonicalizer implements TmJcsCanonicalizer {

    /** {@inheritDoc} */
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
