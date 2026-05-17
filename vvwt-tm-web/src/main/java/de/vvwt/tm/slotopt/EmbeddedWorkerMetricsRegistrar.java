// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
package de.vvwt.tm.slotopt;

/**
 * Marker interface for the embedded-worker metrics registrar (E63S05).
 *
 * <p>DEC-58/DEC-72 Clause A-ext: the {@code @Bean} factory method in {@code
 * EmbeddedWorkerMetricsConfiguration} must return a public first-party interface. The
 * implementation ({@code EmbeddedWorkerMetrics}) is in {@code slotopt.internal} and is not visible
 * to callers.
 *
 * <p>Story: E63S05 AC-GOV-INTERFACE-MANDATE.
 */
public interface EmbeddedWorkerMetricsRegistrar {}
