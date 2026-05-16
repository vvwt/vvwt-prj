// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
package de.vvwt.info.reader.internal;

import de.vvwt.info.reader.HmacTokenValidator;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.Collection;
import java.util.Optional;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Component;

/**
 * Validates URL team tokens using HMAC-SHA256 per Brief D-X3 (c1) (E38S06 AC6).
 *
 * <p>Algorithm: for each {@code team_uuid} in the tournament's registered team list, compute {@code
 * HMAC-SHA256(per_tournament_secret, team_uuid)} and constant-time compare the result with the
 * URL-supplied {@code team_token}. If a match is found, the requesting team is identified.
 *
 * <p>Constant-time comparison via {@link MessageDigest#isEqual(byte[], byte[])} prevents
 * timing-attack oracle recovery of the secret.
 *
 * <p>Token shape: 32-byte HMAC output → Base64URL (no padding) = exactly 43 characters. Tokens that
 * are {@code null}, empty, not exactly 43 chars, or contain non-Base64URL characters are rejected
 * before the HMAC loop (AC9).
 *
 * @see <a href="../../../../../../../../docs/governance/stories/E38S06.story.md">E38S06 AC6,
 *     AC9</a>
 */
@Component
public class DefaultHmacTokenValidator implements HmacTokenValidator {

    /** Expected length of a 32-byte HMAC-SHA256 output when Base64URL-encoded without padding. */
    private static final int EXPECTED_TOKEN_LENGTH = 43;

    /** Base64URL alphabet — no padding character. */
    private static final java.util.regex.Pattern BASE64URL_PATTERN =
            java.util.regex.Pattern.compile("^[A-Za-z0-9_-]+$");

    /**
     * Validates the given {@code teamToken} against all registered team UUIDs and returns the
     * matching team UUID if found.
     *
     * @param perTournamentSecret the 32-byte HMAC secret stored on the tournament row
     * @param teamUuids collection of team UUIDs registered in this tournament (from
     *     tournament.state)
     * @param teamToken the URL-supplied team token (Base64URL encoded, no padding)
     * @return the matching team UUID if validation succeeds; empty if the token is invalid,
     *     malformed, or does not match any registered team
     */
    @Override
    public Optional<String> validateAndResolveTeam(
            byte[] perTournamentSecret, Collection<String> teamUuids, String teamToken) {

        // AC9: shape validation — null, empty, wrong length, non-base64url → uniform rejection
        if (teamToken == null || teamToken.isEmpty()) {
            return Optional.empty();
        }
        if (teamToken.length() != EXPECTED_TOKEN_LENGTH) {
            return Optional.empty();
        }
        if (!BASE64URL_PATTERN.matcher(teamToken).matches()) {
            return Optional.empty();
        }

        // Decode the URL-supplied token once for comparison
        byte[] suppliedBytes;
        try {
            suppliedBytes = Base64.getUrlDecoder().decode(teamToken);
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }

        // AC6: iterate over all team UUIDs with constant-time compare
        for (String teamUuid : teamUuids) {
            byte[] expected = computeHmac(perTournamentSecret, teamUuid);
            if (MessageDigest.isEqual(expected, suppliedBytes)) {
                return Optional.of(teamUuid);
            }
        }
        return Optional.empty();
    }

    /**
     * Computes {@code HMAC-SHA256(secret, data)} where {@code data} is UTF-8 encoded.
     *
     * @param secret the HMAC secret key bytes
     * @param data the data to authenticate (UTF-8 encoded)
     * @return 32-byte HMAC output
     * @throws IllegalStateException if HmacSHA256 is not available (should never happen on any JVM)
     */
    public static byte[] computeHmac(byte[] secret, String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            return mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException("HmacSHA256 not available", e);
        }
    }
}
