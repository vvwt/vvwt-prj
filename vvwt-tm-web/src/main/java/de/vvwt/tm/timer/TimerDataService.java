package de.vvwt.tm.timer;

import de.vvwt.tm.web.internal.dto.TimerDataResponse;
import java.util.UUID;

/**
 * Public service port for the {@code timer} bounded context (DEC-35 + DEC-21 — E26S01).
 *
 * <p>Defines the contract for assembling the timer data response for a tournament. Canonical FQN:
 * {@code de.vvwt.tm.timer.TimerDataService} per DEC-21 module layout. The canonical implementation
 * is {@link de.vvwt.tm.timer.internal.DefaultTimerDataService}.
 *
 * <p>Single public method signature preserved verbatim per C-3 signature-preservation. Consumers
 * of this service (e.g., {@code de.vvwt.tm.web.timer.TimerController} — E26S03) MUST inject this
 * interface type, never the concrete implementation class ({@code DefaultTimerDataService}), per
 * DEC-36 cross-package test typing rule.
 *
 * <h2>Authoring decisions</h2>
 *
 * <ul>
 *   <li>DEC-35 — service interface in public package ({@code de.vvwt.tm.timer}), implementation in
 *       {@code de.vvwt.tm.timer.internal}; naming canon {@code Default*Service} (no I-prefix)
 *   <li>DEC-22 — TDD Iron Law: RED-first test written before implementation
 *   <li>DEC-40 Clause B — return type {@link TimerDataResponse} resides at
 *       {@code de.vvwt.tm.web.internal.dto.*} (web-tier owned HTTP-contract DTO)
 * </ul>
 *
 * @since E26S01
 * @see de.vvwt.tm.timer.internal.DefaultTimerDataService
 * @see <a href="contexts/artefacts/stories/E26S01.story.md">Story E26S01</a>
 */
public interface TimerDataService {

    /**
     * Builds the timer data response for the given tournament.
     *
     * <p>Loads tournament, phases, matches, phase breaks, computes timeline, and assembles audio
     * URLs into a single {@link TimerDataResponse}.
     *
     * @param tournamentId the tournament UUID (must belong to the active tenant per DEC-5)
     * @return the assembled timer data response; never {@code null}
     * @throws InvalidTimerUrlException if the tournament does not exist for the active tenant
     * @throws NoActiveTournamentException if the tournament is in DRAFT or CANCELLED status
     */
    TimerDataResponse buildTimerData(UUID tournamentId);
}
