// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
package de.vvwt.info.persistence.algorithm;

import java.time.LocalDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

/**
 * Spring Data JDBC entity record for the {@code algorithm_registry} table.
 *
 * <p>Maps the algorithm_registry table per DEC-43 D1: each row describes a signature algorithm
 * supported by the server. V1 contains exactly {@code Ed25519} per DEC-43 D4 (AC6). Future PQC
 * algorithms (ML-DSA, SLH-DSA) are Phase-2+ additions under the same table shape.
 *
 * <p>The {@code parameters} column stores JSON-encoded UTF-8 text when non-null (opaque to the DB
 * layer; future PQC parameter sets parsed at the service layer). For Ed25519 Phase 1, {@code
 * parameters} is {@code null}.
 *
 * @param algorithmId server-canonical identifier (e.g., {@code "Ed25519"}) — PK
 * @param displayName human-readable name for operator/admin UIs
 * @param deprecationDate if non-null, algorithm is deprecated and rejected for new registrations
 *     after this date (DEC-43 D3; DEC-48 boundary semantics)
 * @param active whether the algorithm is currently active in the registry
 * @param parameters JSON-encoded algorithm-specific parameter set or {@code null}
 * @see <a href="../../../../../../../docs/governance/stories/E38S03.story.md">E38S03</a>
 * @see <a href="../../../../../../../docs/governance/decisions/DEC-43.md">DEC-43</a>
 */
@Table("algorithm_registry")
public record AlgorithmRegistryRecord(
        @Id @Column("algorithm_id") String algorithmId,
        @Column("display_name") String displayName,
        @Column("deprecation_date") LocalDate deprecationDate,
        @Column("active") boolean active,
        @Column("parameters") String parameters) {}
