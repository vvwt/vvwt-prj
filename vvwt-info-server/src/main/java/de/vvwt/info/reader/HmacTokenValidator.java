// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
package de.vvwt.info.reader;

import java.util.Collection;
import java.util.Optional;

/**
 * Validates URL team tokens using HMAC-SHA256 per Brief D-X3 (c1) (E38S06 AC6).
 *
 * @see de.vvwt.info.reader.internal.DefaultHmacTokenValidator
 * @see <a href="../../../../../../../docs/governance/stories/E38S06.story.md">E38S06 AC6, AC9</a>
 */
public interface HmacTokenValidator {

    /**
     * Validates the given {@code teamToken} against all registered team UUIDs and returns the
     * matching team UUID if found.
     *
     * @param perTournamentSecret the 32-byte HMAC secret stored on the tournament row
     * @param teamUuids collection of team UUIDs registered in this tournament
     * @param teamToken the URL-supplied team token (Base64URL encoded, no padding)
     * @return the matching team UUID if validation succeeds; empty if the token is invalid,
     *     malformed, or does not match any registered team
     */
    Optional<String> validateAndResolveTeam(
            byte[] perTournamentSecret, Collection<String> teamUuids, String teamToken);
}
