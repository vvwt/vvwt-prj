// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
package de.vvwt.info.persistence.algorithm;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import org.junit.jupiter.api.Test;

/**
 * RED-first unit test for {@link AlgorithmRegistryRecord} (DEC-22 Iron Law, AC1).
 *
 * <p>Tests the structural properties of the record: field access, nullability contract, and that
 * the record can be constructed with the expected field types.
 *
 * @see <a href="../../../../../../../docs/governance/stories/E38S03.story.md">E38S03 AC1</a>
 */
class AlgorithmRegistryRecordTest {

    @Test
    void record_fields_accessible() {
        var record = new AlgorithmRegistryRecord("Ed25519", "Ed25519", null, true, null);

        assertThat(record.algorithmId()).isEqualTo("Ed25519");
        assertThat(record.displayName()).isEqualTo("Ed25519");
        assertThat(record.deprecationDate()).isNull();
        assertThat(record.active()).isTrue();
        assertThat(record.parameters()).isNull();
    }

    @Test
    void record_with_deprecation_date_and_parameters() {
        var date = LocalDate.of(2027, 12, 31);
        var record =
                new AlgorithmRegistryRecord(
                        "ml-dsa-65",
                        "ML-DSA-65 (NIST PQC, 2024)",
                        date,
                        true,
                        "{\"keySize\": 1952}");

        assertThat(record.algorithmId()).isEqualTo("ml-dsa-65");
        assertThat(record.deprecationDate()).isEqualTo(date);
        assertThat(record.active()).isTrue();
        assertThat(record.parameters()).isEqualTo("{\"keySize\": 1952}");
    }

    @Test
    void record_equality_by_value() {
        var r1 = new AlgorithmRegistryRecord("Ed25519", "Ed25519", null, true, null);
        var r2 = new AlgorithmRegistryRecord("Ed25519", "Ed25519", null, true, null);
        assertThat(r1).isEqualTo(r2);
    }
}
