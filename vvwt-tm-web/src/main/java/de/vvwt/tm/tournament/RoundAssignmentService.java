// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
package de.vvwt.tm.tournament;

import java.util.UUID;

/**
 * L2 Round-Assignment Service — assigns {@code lapNumber} and {@code fieldNumber} to every match in
 * a phase immediately after L1 (Match-Generation) and before L3 (Slot-Optimization).
 *
 * <h2>Contract</h2>
 *
 * <p>Given the set of matches for a phase (produced by L1 with {@code lapNumber=null,
 * fieldNumber=null}), this service assigns lap and field coordinates such that:
 *
 * <ol>
 *   <li><strong>Round-conflict-freedom:</strong> for any {@code lapNumber L}, no team (avatar)
 *       appears in two matches that share that lap.
 *   <li><strong>Field-count constraint:</strong> each lap holds at most {@code fieldCount} matches;
 *       no {@code fieldNumber} value exceeds {@code fieldCount - 1}.
 *   <li><strong>Flat lap-major ordering:</strong> position index {@code i} (across all matches,
 *       ordered by lap then position within lap) maps to {@code lapNumber = i / fieldCount} and
 *       {@code fieldNumber = i % fieldCount}. Partial trailing laps occupy only {@code fieldNumber
 *       0..k-1} where {@code k} is the number of matches in that lap.
 * </ol>
 *
 * <h2>Spielart-agnostic</h2>
 *
 * <p>This interface operates on the match list without knowledge of the upstream game mode
 * (Spielart). It does NOT reference {@code roundRobin}, {@code gameMode}, or any generator-
 * specific type. This ensures L2 works for all current and future Spielarten without algorithm
 * change (Brief Q-3 + operator-confirmed 2026-05-09).
 *
 * <h2>Transaction semantics</h2>
 *
 * <p>Implementations MUST execute within the caller's transaction. All lap+field writes are atomic
 * with the enclosing {@code REQUIRES_NEW} transaction in {@code MatchGenJobExecutor}. A failure
 * mid-write rolls back all writes (DEC-37 Clause B; AC-ERROR-HANDLING-NO-PARTIAL-PERSIST).
 *
 * <h2>Siegerehrung / empty-phase handling</h2>
 *
 * <p>If the phase has 0 matches, implementations MUST be a no-op (no exception, no DB writes).
 *
 * @see de.vvwt.tm.tournament.internal.DefaultRoundAssignmentService
 * @see <a href="DEC-9">DEC-9 — TeamAvatar structural identity</a>
 * @see <a href="DEC-22">DEC-22 — TDD Iron Law</a>
 * @see <a href="DEC-35">DEC-35 — Spring Modulith package layout (public interface in module
 *     root)</a>
 * @see <a href="DEC-55">DEC-55 D-3 — Background-Job-Pipeline (L2 writes lap+field after L1)</a>
 * @see <a href="E51S10">E51S10 — L2 Round-Assignment Service story</a>
 */
public interface RoundAssignmentService {

    /**
     * Assigns {@code lapNumber} and {@code fieldNumber} to every match in the given phase.
     *
     * <p>The assignment respects round-conflict-freedom (no team plays twice in the same lap), the
     * field-count capacity constraint (≤ {@code fieldCount} matches per lap), and the flat
     * lap-major ordering contract (position {@code i} → {@code lap = i / fieldCount}, {@code field
     * = i % fieldCount}).
     *
     * <p>Multi-group phases (L1 producing matches for multiple {@code groupNumber} values): groups
     * are processed independently, each receiving its own lap sequence. Group sequences are
     * concatenated in ascending {@code groupNumber} order — Group A occupies laps {@code 1..k1},
     * Group B occupies laps {@code k1+1..k1+k2} (Brief D-12 concatenation convention; 1-based).
     *
     * <p>Worked example (from javadoc contract):
     *
     * <pre>
     * fieldCount = 3, 6 matches in a single group (1-based lap numbers, E53S06):
     *   i=0 → lap=1, field=0
     *   i=1 → lap=1, field=1
     *   i=2 → lap=1, field=2
     *   i=3 → lap=2, field=0
     *   i=4 → lap=2, field=1
     *   i=5 → lap=2, field=2
     * (each pair of avatars appears at most once per lap — round-conflict-freedom met)
     * </pre>
     *
     * @param phaseId the UUID of the phase whose matches are to be assigned; must not be {@code
     *     null}
     * @param fieldCount the number of courts; must be ≥ 1 (validated before call per D-13 fallback
     *     in {@code MatchGenJobExecutor})
     * @throws IllegalArgumentException if {@code phaseId} is {@code null} or {@code fieldCount} < 1
     */
    void assignRoundsAndFields(UUID phaseId, int fieldCount);
}
