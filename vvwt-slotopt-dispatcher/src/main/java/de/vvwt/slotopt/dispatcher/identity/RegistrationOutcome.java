// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
package de.vvwt.slotopt.dispatcher.identity;

/**
 * Outcome of a key-registration attempt.
 *
 * <p>Carries the {@link RegisterKeyResponse} along with a {@code created} flag that indicates
 * whether a NEW registration was persisted ({@code true}) or an idempotent re-registration returned
 * an existing record ({@code false}).
 *
 * <p>The controller uses this flag to choose between HTTP 201 (new) and HTTP 200 (idempotent).
 *
 * <p>Story: E37S05; AC-REGISTER-KEY-CONTROLLER
 */
public record RegistrationOutcome(RegisterKeyResponse response, boolean isNew) {}
