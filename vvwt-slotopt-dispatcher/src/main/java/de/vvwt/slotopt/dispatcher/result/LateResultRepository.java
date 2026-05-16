// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
package de.vvwt.slotopt.dispatcher.result;

import org.springframework.data.repository.CrudRepository;

/**
 * Spring Data JDBC repository for {@link LateResult}.
 *
 * <p>Per DEC-35: Spring Data {@code CrudRepository} interfaces ARE the port by definition. No
 * separate public interface wrapper is needed (Spring-Data carve-out).
 *
 * <p>Story: E37S09; AC-LATE-RESULT-REPOSITORY; DEC-35
 */
public interface LateResultRepository extends CrudRepository<LateResult, Long> {}
