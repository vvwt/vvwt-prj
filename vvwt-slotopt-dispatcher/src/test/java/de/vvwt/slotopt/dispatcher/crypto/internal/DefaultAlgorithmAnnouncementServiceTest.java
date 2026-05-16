// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
package de.vvwt.slotopt.dispatcher.crypto.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import de.vvwt.slotopt.dispatcher.crypto.AlgorithmAnnouncementService;
import de.vvwt.slotopt.dispatcher.crypto.AnnouncedAlgorithm;
import de.vvwt.slotopt.dispatcher.crypto.SignatureVerifier;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Unit tests for {@link DefaultAlgorithmAnnouncementService}.
 *
 * <p>RED-first per DEC-22 Iron Law / AC-TDD-RED-FIRST-EVIDENCE / AC-DEFAULT-ANNOUNCEMENT-SERVICE.
 * Written before {@link DefaultAlgorithmAnnouncementService} exists — compilation fails until Step
 * 3b of the plan.
 *
 * <p>DEC-36 cross-package note: this test class is in the SAME package as the subject ({@code
 * crypto.internal}), so white-box reference to {@link DefaultAlgorithmAnnouncementService} is
 * permitted. The service field is typed as {@link AlgorithmAnnouncementService} (interface) for the
 * query/interface tests, and as {@link DefaultAlgorithmAnnouncementService} for white-box
 * construction.
 *
 * <p>Tests:
 *
 * <ul>
 *   <li>Single verifier → single AnnouncedAlgorithm (AC-DEFAULT-ANNOUNCEMENT-SERVICE)
 *   <li>Two verifiers → two AnnouncedAlgorithms (AC-V2-EXTENSIBILITY-CONTRACT)
 *   <li>Empty verifier list → empty announcement list (AC-EMPTY-VERIFIER-LIST)
 *   <li>Metadata field mapping (algorithmId, displayName, deprecationDate, parameters)
 * </ul>
 *
 * <p>Story: E40S02
 */
@ExtendWith(MockitoExtension.class)
class DefaultAlgorithmAnnouncementServiceTest {

    @Mock private SignatureVerifier verifier1;
    @Mock private SignatureVerifier verifier2;

    // -------------------------------------------------------------------------
    // Single verifier — AC-DEFAULT-ANNOUNCEMENT-SERVICE
    // -------------------------------------------------------------------------

    @Test
    void singleVerifierMapsToSingleAnnouncedAlgorithm() {
        when(verifier1.algorithmId()).thenReturn("Ed25519");
        when(verifier1.displayName()).thenReturn("Ed25519");
        when(verifier1.deprecationDate()).thenReturn(null);
        when(verifier1.parameters()).thenReturn(null);

        AlgorithmAnnouncementService service =
                new DefaultAlgorithmAnnouncementService(List.of(verifier1));

        List<AnnouncedAlgorithm> result = service.announcedAlgorithms();

        assertThat(result).hasSize(1);
        AnnouncedAlgorithm announced = result.get(0);
        assertThat(announced.algorithmId()).isEqualTo("Ed25519");
        assertThat(announced.displayName()).isEqualTo("Ed25519");
        assertThat(announced.deprecationDate()).isNull();
        assertThat(announced.parameters()).isNull();
    }

    // -------------------------------------------------------------------------
    // Two verifiers — AC-V2-EXTENSIBILITY-CONTRACT
    // -------------------------------------------------------------------------

    @Test
    void twoVerifiersMapToTwoAnnouncedAlgorithms_v2ExtensibilityContract() {
        when(verifier1.algorithmId()).thenReturn("Ed25519");
        when(verifier1.displayName()).thenReturn("Ed25519");
        when(verifier1.deprecationDate()).thenReturn(null);
        when(verifier1.parameters()).thenReturn(null);

        when(verifier2.algorithmId()).thenReturn("ML-DSA-65");
        when(verifier2.displayName()).thenReturn("ML-DSA-65");
        when(verifier2.deprecationDate()).thenReturn(null);
        when(verifier2.parameters()).thenReturn(Map.of("parameter_set", "ML-DSA-65"));

        AlgorithmAnnouncementService service =
                new DefaultAlgorithmAnnouncementService(List.of(verifier1, verifier2));

        List<AnnouncedAlgorithm> result = service.announcedAlgorithms();

        assertThat(result).hasSize(2);

        // First algorithm (Ed25519)
        AnnouncedAlgorithm ed25519 =
                result.stream()
                        .filter(a -> "Ed25519".equals(a.algorithmId()))
                        .findFirst()
                        .orElseThrow();
        assertThat(ed25519.parameters()).isNull();

        // Second algorithm (ML-DSA-65)
        AnnouncedAlgorithm mlDsa =
                result.stream()
                        .filter(a -> "ML-DSA-65".equals(a.algorithmId()))
                        .findFirst()
                        .orElseThrow();
        assertThat(mlDsa.parameters()).containsEntry("parameter_set", "ML-DSA-65");
    }

    // -------------------------------------------------------------------------
    // Empty verifier list — AC-EMPTY-VERIFIER-LIST (service side)
    // -------------------------------------------------------------------------

    @Test
    void emptyVerifierListReturnsEmptyAnnouncedAlgorithmList() {
        AlgorithmAnnouncementService service = new DefaultAlgorithmAnnouncementService(List.of());

        List<AnnouncedAlgorithm> result = service.announcedAlgorithms();

        assertThat(result).isEmpty();
    }

    // -------------------------------------------------------------------------
    // Metadata field propagation (deprecation date + parameters)
    // -------------------------------------------------------------------------

    @Test
    void verifierWithDeprecationDatePropagatesDate() {
        LocalDate deprecation = LocalDate.of(2026, 12, 31);
        when(verifier1.algorithmId()).thenReturn("Ed25519");
        when(verifier1.displayName()).thenReturn("Ed25519");
        when(verifier1.deprecationDate()).thenReturn(deprecation);
        when(verifier1.parameters()).thenReturn(null);

        AlgorithmAnnouncementService service =
                new DefaultAlgorithmAnnouncementService(List.of(verifier1));

        List<AnnouncedAlgorithm> result = service.announcedAlgorithms();

        assertThat(result).hasSize(1);
        assertThat(result.get(0).deprecationDate()).isEqualTo(deprecation);
    }
}
