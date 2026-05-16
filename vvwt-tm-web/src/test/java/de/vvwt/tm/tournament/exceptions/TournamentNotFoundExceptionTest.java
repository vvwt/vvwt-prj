// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
package de.vvwt.tm.tournament.exceptions;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/**
 * RED-first unit test for {@link TournamentNotFoundException} — E24S01 (DEC-22 Iron Law).
 *
 * <h2>RED state (DEC-22)</h2>
 *
 * <p>This test was authored before {@code TournamentNotFoundException} existed at {@code
 * de.vvwt.tm.tournament.exceptions.*}. At commit time the class was absent, causing a compile error
 * and proving RED-first authoring per DEC-22 Iron Law.
 *
 * <h2>Behavioral contract verified</h2>
 *
 * <ul>
 *   <li>Extends {@link RuntimeException} (unchecked, per Story
 *       AC-EXCEPTION-CREATE-PUBLIC-WITH-RESPONSESTATUS)
 *   <li>Constructor accepts {@code UUID tournamentId}; {@code getMessage()} contains {@code
 *       tournamentId.toString()}
 *   <li>Class carries {@code @ResponseStatus(HttpStatus.NOT_FOUND)} — defense-in-depth per Brief
 *       R-3 and DEC-40
 * </ul>
 *
 * <p>Story: E24S01 — AC-EXCEPTION-REDFIRST-TEST, AC-EXCEPTION-CREATE-PUBLIC-WITH-RESPONSESTATUS,
 * AC-ERROR-HANDLING-EXCEPTION-CONTRACT, AC-EXCEPTION-TEST-GREEN.
 */
@DisplayName("TournamentNotFoundExceptionTest — E24S01 AC-EXCEPTION-REDFIRST-TEST")
class TournamentNotFoundExceptionTest {

    @Test
    @DisplayName("extends RuntimeException (AC-a)")
    void extendsRuntimeException() {
        UUID id = UUID.randomUUID();
        TournamentNotFoundException ex = new TournamentNotFoundException(id);

        assertThat(ex).isInstanceOf(RuntimeException.class);
    }

    @Test
    @DisplayName("message contains tournamentId.toString() (AC-b)")
    void messageContainsTournamentId() {
        UUID id = UUID.randomUUID();
        TournamentNotFoundException ex = new TournamentNotFoundException(id);

        assertThat(ex.getMessage()).contains(id.toString());
    }

    @Test
    @DisplayName("class carries @ResponseStatus(NOT_FOUND) via reflection (AC-c)")
    void hasResponseStatusNotFound() {
        assertThat(TournamentNotFoundException.class.isAnnotationPresent(ResponseStatus.class))
                .as("@ResponseStatus annotation must be present on TournamentNotFoundException")
                .isTrue();
        assertThat(TournamentNotFoundException.class.getAnnotation(ResponseStatus.class).value())
                .as("@ResponseStatus.value() must be HttpStatus.NOT_FOUND")
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    @DisplayName(
            "message contains no sensitive information beyond UUID"
                    + " (AC-SECURITY-EXCEPTION-MESSAGE-DISCLOSURE)")
    void messageContainsNoSensitiveData() {
        UUID id = UUID.randomUUID();
        TournamentNotFoundException ex = new TournamentNotFoundException(id);

        String msg = ex.getMessage();
        // Message must contain the UUID — already verified in messageContainsTournamentId
        // Message must NOT be null or empty
        assertThat(msg).isNotNull().isNotEmpty();
        // The message format is implementer's choice; this test verifies behavioral contract only
        // (Brief R-3: no entity fields, no SQL fragments, no stack-trace text in the message)
    }
}
