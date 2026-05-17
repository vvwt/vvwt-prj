// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
package de.vvwt.slotopt.worker.identity;

import static org.assertj.core.api.Assertions.assertThat;

import de.vvwt.slotopt.worker.identity.internal.DefaultWorkerCapabilityProvider;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Cross-package contract test for {@link WorkerCapabilityProvider}.
 *
 * <p>Per DEC-36: this test is in a different package from the implementation ({@code
 * de.vvwt.slotopt.worker.identity} vs {@code de.vvwt.slotopt.worker.identity.internal}), so the
 * subject is referenced via the public interface type {@link WorkerCapabilityProvider}.
 *
 * <p>Validates the contract that any {@link WorkerCapabilityProvider} implementation must satisfy.
 *
 * <p>See E37S03, DEC-35, DEC-36, AC-DEC36-CROSS-PACKAGE-TESTS.
 */
class WorkerCapabilityProviderContractTest {

    /**
     * Contract: supportedAlgorithms() must return a non-null, non-empty set.
     *
     * <p>References the subject via the public {@link WorkerCapabilityProvider} interface type per
     * DEC-36 cross-package test typing rule.
     */
    @Test
    void supportedAlgorithmsIsNonNullAndNonEmpty() {
        WorkerCapabilityProvider provider = new DefaultWorkerCapabilityProvider();

        Set<String> algorithms = provider.supportedAlgorithms();

        assertThat(algorithms)
                .as("supportedAlgorithms() must return a non-null, non-empty set")
                .isNotNull()
                .isNotEmpty();
    }

    @Test
    void supportedAlgorithmsContainsOnlyNonNullEntries() {
        WorkerCapabilityProvider provider = new DefaultWorkerCapabilityProvider();

        Set<String> algorithms = provider.supportedAlgorithms();

        assertThat(algorithms)
                .as("All algorithm identifiers must be non-null strings")
                .doesNotContainNull();
    }
}
