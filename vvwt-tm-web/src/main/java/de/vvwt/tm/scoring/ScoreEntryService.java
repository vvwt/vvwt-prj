// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
package de.vvwt.tm.scoring;

import java.util.Optional;

/**
 * Public service port for scoring-tablet interactions in the {@code scoring} bounded context
 * (E22S06, DEC-35, AC-INTERFACE-CREATED).
 *
 * <p>Defines the contract for device-token-authenticated scoring operations. The canonical
 * implementation is {@link de.vvwt.tm.scoring.internal.DefaultScoreEntryService}.
 *
 * <p>Method signatures are preserved verbatim from legacy {@code
 * de.vvwt.tm.infrastructure.score.ScoreEntryService} with DTO types renamed to scoring-public
 * records (Q-11 transitive-exposure rule — parameter and return types of this public interface MUST
 * be in the public package):
 *
 * <ul>
 *   <li>{@code getMatchForField} — preserves legacy {@code Optional<MatchScoreResponse>} return
 *       (type renamed to {@link ScoreEntryResult}); legacy {@code deviceToken} is {@code String}
 *       (not {@code UUID}) per legacy signature.
 *   <li>{@code handlePartialScore} — preserves legacy {@code void} return; parameter renamed from
 *       {@code PartialScoreRequest} to {@link PartialScoreInput}.
 *   <li>{@code submitSetResult} — preserves legacy {@code void} return (HTTP 204); parameter
 *       renamed from {@code SetSubmitRequest} to {@link SetSubmitInput}.
 * </ul>
 *
 * <p>This interface is authored from the start as a public port (not post-hoc extracted) because
 * E22S09's {@code ScoreApiController} will consume it across module boundaries per DEC-21 without
 * requiring an interface-extraction refactor at S09 pickup (Interface-at-authoring decision, Brief
 * Q-11).
 *
 * <h2>Security contract</h2>
 *
 * <p>All three methods validate the {@code deviceToken} parameter (or the token inside the request
 * record) before any business logic. See {@code DefaultScoreEntryService} for the full validation
 * sequence (AC-SECURITY-DEVICE-TOKEN).
 *
 * <h2>DEC compliance</h2>
 *
 * <ul>
 *   <li>DEC-35 — interface in {@code scoring} public package; implementation in {@code
 *       scoring.internal}; naming canon {@code Default*Service}
 *   <li>DEC-22 — TDD Iron Law: all methods were preceded by failing tests before the first
 *       implementation line was authored
 *   <li>DEC-37 Clause B — {@code submitSetResult} delegates cascade to {@link
 *       ScoringService#registerMatchResult} which holds the per-tournament DB lock
 * </ul>
 *
 * @since E22S06
 * @see de.vvwt.tm.scoring.internal.DefaultScoreEntryService
 * @see <a href="DEC-35">DEC-35 — Spring Modulith package layout</a>
 * @see <a href="DEC-22">DEC-22 — TDD Iron Law</a>
 * @see <a href="DEC-37">DEC-37 — cascade serialization via per-tournament pessimistic DB lock</a>
 */
public interface ScoreEntryService {

    /**
     * Returns the active match display data for the given field, or empty if none.
     *
     * <p>Validates the {@code deviceToken} before resolving the match. The active tournament →
     * active phase → current lap → non-terminal match on the field resolution chain is followed.
     * Team names are resolved from {@code TeamAvatar} → {@code Team}.
     *
     * @param fieldNumber the court field number (1-based)
     * @param deviceToken the tablet's opaque device token (NOT NULL)
     * @return populated result if an active match exists; empty if not
     * @throws de.vvwt.tm.tournament.exceptions.UnauthorizedException if the device token is invalid
     *     or unassigned
     * @throws de.vvwt.tm.tournament.exceptions.ForbiddenException if the device is valid but
     *     assigned to a different field
     * @throws IllegalArgumentException if {@code deviceToken} is {@code null}
     */
    Optional<ScoreEntryResult> getMatchForField(int fieldNumber, String deviceToken);

    /**
     * Accepts a partial (in-progress) score update and broadcasts it via WebSocket.
     *
     * <p>The device token (inside {@code request.deviceToken()}) is validated before broadcasting.
     * The score is NOT persisted — partial updates are transient live-score signals.
     *
     * @param request the partial score input (NOT NULL)
     * @throws de.vvwt.tm.tournament.exceptions.UnauthorizedException if the device token is invalid
     *     or unassigned
     * @throws de.vvwt.tm.tournament.exceptions.ForbiddenException if the device is valid but not
     *     authorized for the match
     * @throws IllegalArgumentException if {@code request} is {@code null}
     */
    void handlePartialScore(PartialScoreInput request);

    /**
     * Submits a final set result, delegating cascade recompute to {@link ScoringService}.
     *
     * <p>Validates the device token (inside {@code request.deviceToken()}) before delegating to
     * {@link ScoringService#registerMatchResult} which acquires the per-tournament pessimistic DB
     * row-lock (DEC-37 Clause B). This method MUST NOT acquire the lock directly.
     *
     * @param request the set submission input (NOT NULL)
     * @throws de.vvwt.tm.tournament.exceptions.UnauthorizedException if the device token is invalid
     *     or unassigned
     * @throws de.vvwt.tm.tournament.exceptions.ForbiddenException if the device is valid but not
     *     assigned to the match's field
     * @throws de.vvwt.tm.tournament.exceptions.ValidationException if the set score fails
     *     validation (propagated from {@link ScoringService})
     * @throws IllegalArgumentException if {@code request} is {@code null}
     */
    void submitSetResult(SetSubmitInput request);
}
