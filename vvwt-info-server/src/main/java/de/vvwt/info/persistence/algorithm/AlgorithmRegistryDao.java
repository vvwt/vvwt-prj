// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
package de.vvwt.info.persistence.algorithm;

import org.springframework.data.repository.CrudRepository;

/**
 * Spring Data JDBC repository for {@link AlgorithmRegistryRecord}.
 *
 * <p>Provides CRUD access to the {@code algorithm_registry} table. The V1 seed row ({@code
 * Ed25519}) is inserted by Flyway migration {@code V2__algorithm_registry_seed.sql} per DEC-43 D4
 * (AC6). Future algorithm additions are code-only under the same protocol shape (DEC-43 D4).
 *
 * @see <a href="../../../../../../../docs/governance/stories/E38S03.story.md">E38S03</a>
 * @see <a href="../../../../../../../docs/governance/decisions/DEC-43.md">DEC-43</a>
 */
public interface AlgorithmRegistryDao extends CrudRepository<AlgorithmRegistryRecord, String> {}
