// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
package de.vvwt.slotopt.dispatcher.identity;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.repository.CrudRepository;

/**
 * Spring Data JDBC repository for {@link KeyRegistration} entities.
 *
 * <p>Per DEC-35: the repository interface is in the public {@code identity} package (Spring Data IS
 * the port — the interface maps cleanly to Spring Data CRUD semantics). No implementation class is
 * required; Spring Data generates one at runtime.
 *
 * <p>Per DEC-35 repository-contract selection rule: because this repository's contract maps cleanly
 * to Spring Data CRUD semantics, {@code CrudRepository} IS the port. No hand-authored port
 * interface is needed.
 *
 * <p>Story: E37S05; AC-KEY-REGISTRATION-REPOSITORY
 */
public interface KeyRegistrationRepository extends CrudRepository<KeyRegistration, Long> {

    /**
     * Finds a registration by the worker's UUID.
     *
     * @param workerId the worker ID to look up
     * @return an {@link Optional} containing the registration if present, or empty if no
     *     registration exists for this worker ID
     */
    Optional<KeyRegistration> findByWorkerId(UUID workerId);
}
