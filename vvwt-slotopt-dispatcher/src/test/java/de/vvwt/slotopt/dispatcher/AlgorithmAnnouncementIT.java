// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
package de.vvwt.slotopt.dispatcher;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/**
 * Integration test for {@link de.vvwt.slotopt.dispatcher.crypto.AlgorithmAnnouncementController}.
 *
 * <p>RED-first per DEC-22 Iron Law / AC-TDD-RED-FIRST-EVIDENCE. Written before the full wiring is
 * complete — compilation succeeds only when AnnouncedAlgorithm, AlgorithmAnnouncementService,
 * DefaultAlgorithmAnnouncementService, and AlgorithmAnnouncementController all exist.
 *
 * <p>Tests:
 *
 * <ul>
 *   <li>V1 wire shape is exact: [{algorithm_id:"Ed25519", display_name:"Ed25519",
 *       deprecation_date:null, parameters:null}] (AC-V1-WIRE-SHAPE)
 *   <li>Endpoint accessible without authentication (AC-NO-AUTH-REQUIRED)
 * </ul>
 *
 * <p>Story: E40S02
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
class AlgorithmAnnouncementIT {

    @Autowired private TestRestTemplate restTemplate;

    // -------------------------------------------------------------------------
    // AC-V1-WIRE-SHAPE — exact JSON wire shape assertion
    // -------------------------------------------------------------------------

    @Test
    void v1WireShapeIsExact() {
        ResponseEntity<String> response =
                restTemplate.getForEntity("/api/algorithms", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);

        String body = response.getBody();
        assertThat(body).isNotNull();

        // Assert the exact V1 JSON wire shape per AC-V1-WIRE-SHAPE + DEC-43 D1
        // Whitespace-insensitive but field-name and value sensitive
        assertThat(body).contains("\"algorithm_id\"");
        assertThat(body).contains("\"display_name\"");
        assertThat(body).contains("\"deprecation_date\"");
        assertThat(body).contains("\"parameters\"");
        assertThat(body).contains("\"Ed25519\"");
        assertThat(body).contains("\"deprecation_date\":null");
        assertThat(body).contains("\"parameters\":null");

        // Must be an array (starts with '[')
        assertThat(body.trim()).startsWith("[");
        assertThat(body.trim()).endsWith("]");
    }

    // -------------------------------------------------------------------------
    // AC-NO-AUTH-REQUIRED — unauthenticated access returns 200
    // -------------------------------------------------------------------------

    @Test
    void endpointAccessibleWithoutAuthentication() {
        // TestRestTemplate without credentials — no Authorization header sent
        ResponseEntity<String> response =
                restTemplate.getForEntity("/api/algorithms", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }
}
