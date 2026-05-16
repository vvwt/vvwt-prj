// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
package de.vvwt.slotopt.standalone.http;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Cross-package interface-contract tests for {@link DispatcherClient}.
 *
 * <p>Test class is in the {@code http} package — same package as the interface. Per DEC-36:
 * cross-package consumers must type-reference via interface; same-package tests may reference
 * concrete types. Here we verify the interface contract by mocking the interface.
 *
 * <p>Story: E41S04 AC-DISPATCHER-CLIENT-INTERFACE.
 */
class DispatcherClientTest {

    /** TC-3 + TC-4: fetchAnnouncedAlgorithms() returns AnnouncedAlgorithmsResponse. */
    @Test
    void fetchAnnouncedAlgorithms_returns_response() throws Exception {
        DispatcherClient client = mock(DispatcherClient.class);
        AnnouncedAlgorithm algo = new AnnouncedAlgorithm("Ed25519", "Ed25519", null, null);
        AnnouncedAlgorithmsResponse response = new AnnouncedAlgorithmsResponse(List.of(algo));
        when(client.fetchAnnouncedAlgorithms()).thenReturn(response);

        AnnouncedAlgorithmsResponse result = client.fetchAnnouncedAlgorithms();

        assertThat(result.algorithms()).hasSize(1);
        assertThat(result.algorithms().get(0).algorithmId()).isEqualTo("Ed25519");
    }

    /**
     * TC-5: registerKey() is declared with RegisterKeyRequest parameter, returns
     * RegisterKeyResponse.
     */
    @Test
    void registerKey_is_declared() throws Exception {
        DispatcherClient client = mock(DispatcherClient.class);
        RegisterKeyRequest request =
                new RegisterKeyRequest(UUID.randomUUID(), "worker", "Ed25519", new byte[32]);
        RegisterKeyResponse response =
                new RegisterKeyResponse(UUID.randomUUID(), "worker", "Ed25519", null);
        when(client.registerKey(request)).thenReturn(response);

        RegisterKeyResponse result = client.registerKey(request);

        assertThat(result.algorithm()).isEqualTo("Ed25519");
    }

    /** TC-3b: AnnouncedAlgorithmsResponse wraps a list of AnnouncedAlgorithm. */
    @Test
    void announcedAlgorithmsResponse_wraps_list() {
        AnnouncedAlgorithm algo1 = new AnnouncedAlgorithm("Ed25519", "Ed25519", null, null);
        AnnouncedAlgorithm algo2 =
                new AnnouncedAlgorithm("ML-DSA-65", "ML-DSA-65", LocalDate.of(2030, 1, 1), null);
        AnnouncedAlgorithmsResponse response =
                new AnnouncedAlgorithmsResponse(List.of(algo1, algo2));

        assertThat(response.algorithms()).hasSize(2);
        assertThat(response.algorithms().get(1).deprecationDate())
                .isEqualTo(LocalDate.of(2030, 1, 1));
    }

    /** TC-3c: AnnouncedAlgorithm carries all DEC-43 D1 fields. */
    @Test
    void announcedAlgorithm_carries_all_fields() {
        LocalDate depDate = LocalDate.of(2027, 12, 31);
        AnnouncedAlgorithm algo =
                new AnnouncedAlgorithm("Ed25519", "Ed25519 (RFC 8032)", depDate, null);

        assertThat(algo.algorithmId()).isEqualTo("Ed25519");
        assertThat(algo.displayName()).isEqualTo("Ed25519 (RFC 8032)");
        assertThat(algo.deprecationDate()).isEqualTo(depDate);
        assertThat(algo.parameters()).isNull();
    }
}
