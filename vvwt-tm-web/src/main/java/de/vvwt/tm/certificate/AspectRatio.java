// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
package de.vvwt.tm.certificate;

/**
 * Immutable value object representing a width-to-height aspect ratio for the team photo crop step
 * (E71S02 AC2).
 *
 * <p>Returned by {@link CertificateTemplateService#retrieveEffectiveAspectRatio(java.util.UUID)} as
 * the effective crop ratio to use: either the per-template override (when set on the {@link
 * CertificateTemplateMetadata}) or the global default from {@code tm.photos}.
 *
 * @param width width component of the aspect ratio (positive integer)
 * @param height height component of the aspect ratio (positive integer)
 */
public record AspectRatio(int width, int height) {}
