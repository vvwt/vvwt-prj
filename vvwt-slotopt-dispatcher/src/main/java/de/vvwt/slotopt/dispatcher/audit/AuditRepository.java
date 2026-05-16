// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
package de.vvwt.slotopt.dispatcher.audit;

import org.springframework.data.repository.CrudRepository;

/**
 * Spring Data repository for {@link AuditEntry}.
 *
 * <p>Per DEC-35: the Spring Data {@code CrudRepository} interface IS the port — the contract maps
 * cleanly to Spring Data CRUD semantics, so no hand-authored wrapper is needed. The interface lives
 * in the public {@code audit} package per DEC-35.
 *
 * <p>All consumers (services, tests) reference this interface, never the Spring Data
 * generated-proxy directly (DEC-36).
 *
 * <p>Story: E37S06; AC-AUDIT-REPOSITORY
 */
public interface AuditRepository extends CrudRepository<AuditEntry, Long> {}
