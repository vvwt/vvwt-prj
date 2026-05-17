// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
package de.vvwt.slotopt.dispatcher.result;

/**
 * Immutable DTO carrying the best optimization result across a set of packets.
 *
 * <p>Used in two contexts:
 *
 * <ul>
 *   <li>{@link JobStatusResponse#finalResult()} — the aggregated global optimum for a {@code
 *       COMPLETED} job
 *   <li>{@link JobStatusResponse#bestSoFar()} — the best result among completed packets for an
 *       in-progress job
 * </ul>
 *
 * <p>DEC-9: carries only structural optimization data — {@code bestRank} / {@code bestScore}. No
 * team UUIDs, names, or identity-bearing attributes cross the optimizer service boundary.
 *
 * <p>AC-GOV-DEC9-STRUCTURAL-ONLY: fields are deliberately limited to the two scalar structural
 * values; the serialized JSON exposes exactly {@code bestRank} and {@code bestScore}.
 *
 * <p>Story: E60S04; AC-TEST-JOB-STATUS-FINAL-RESULT; AC-TEST-JOB-STATUS-BEST-SO-FAR;
 * AC-GOV-DEC9-STRUCTURAL-ONLY; DEC-9, DEC-35
 */
public record OptimumResult(int bestRank, double bestScore) {}
