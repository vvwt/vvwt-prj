// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
package de.vvwt.tm.tournament.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.vvwt.tm.tournament.Phase;
import de.vvwt.tm.tournament.PhaseRepository;
import de.vvwt.tm.tournament.Tournament;
import de.vvwt.tm.tournament.TournamentLifecycleSupport;
import de.vvwt.tm.tournament.TournamentRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link DefaultTournamentLifecycleSupport} — covering:
 *
 * <ul>
 *   <li>AC-TEST-IS-LAST-PHASE-RED — isLastPhase happy + edge paths
 *   <li>AC-TEST-DRAFT-JSON-NULL-DEFENSIVE-RED — readGameMode null / invalid-JSON paths
 * </ul>
 *
 * <p>RED-first per DEC-22 Iron Law: tests were written before the production class existed.
 *
 * <h2>DEC-36 cross-package test typing</h2>
 *
 * <p>This test class resides in {@code de.vvwt.tm.tournament.internal} — the SAME package as {@link
 * DefaultTournamentLifecycleSupport}. Per DEC-36 same-package rule, white-box access is permitted.
 * The field {@code service} is typed as {@link TournamentLifecycleSupport} (the public interface)
 * to verify that the implementation satisfies the public contract; {@code impl} is typed as {@link
 * DefaultTournamentLifecycleSupport} where white-box access to package-visible methods is needed
 * (e.g., {@code readGameMode}).
 *
 * @see DefaultTournamentLifecycleSupport
 * @see TournamentLifecycleSupport
 * @see <a href="DEC-22">DEC-22 — TDD Iron Law</a>
 * @see <a href="DEC-36">DEC-36 — cross-package test typing (same-package: white-box permitted)</a>
 * @see <a href="E48S02">E48S02 — AC-TEST-IS-LAST-PHASE-RED,
 *     AC-TEST-DRAFT-JSON-NULL-DEFENSIVE-RED</a>
 */
@DisplayName("DefaultTournamentLifecycleSupport — isLastPhase + readGameMode unit tests")
class TournamentLifecycleSupportTest {

    private PhaseRepository phaseRepository;
    private TournamentRepository tournamentRepository;
    private TournamentLifecycleSupport service;
    private DefaultTournamentLifecycleSupport impl;

    private UUID tournamentId;

    @BeforeEach
    void setUp() {
        phaseRepository = mock(PhaseRepository.class);
        tournamentRepository = mock(TournamentRepository.class);
        impl = new DefaultTournamentLifecycleSupport(phaseRepository, tournamentRepository);
        service = impl;
        tournamentId = UUID.randomUUID();
    }

    // =========================================================================
    // AC-TEST-IS-LAST-PHASE-RED — isLastPhase
    // =========================================================================

    @Test
    @DisplayName("isLastPhase returns true for the phase with the highest sequenceNumber")
    void isLastPhase_lastPhase_returnsTrue() {
        UUID phase1Id = UUID.randomUUID();
        UUID phase2Id = UUID.randomUUID();
        UUID phase3Id = UUID.randomUUID();

        when(phaseRepository.findById(phase3Id))
                .thenReturn(Optional.of(buildPhase(phase3Id, tournamentId, 3)));
        when(phaseRepository.findByTournamentId(tournamentId))
                .thenReturn(
                        List.of(
                                buildPhase(phase1Id, tournamentId, 1),
                                buildPhase(phase2Id, tournamentId, 2),
                                buildPhase(phase3Id, tournamentId, 3)));

        assertThat(service.isLastPhase(phase3Id)).isTrue();
    }

    @Test
    @DisplayName(
            "isLastPhase returns false for a non-last phase (sequenceNumber 1 in 3-phase"
                    + " tournament)")
    void isLastPhase_firstOfThree_returnsFalse() {
        UUID phase1Id = UUID.randomUUID();
        UUID phase2Id = UUID.randomUUID();
        UUID phase3Id = UUID.randomUUID();

        when(phaseRepository.findById(phase1Id))
                .thenReturn(Optional.of(buildPhase(phase1Id, tournamentId, 1)));
        when(phaseRepository.findByTournamentId(tournamentId))
                .thenReturn(
                        List.of(
                                buildPhase(phase1Id, tournamentId, 1),
                                buildPhase(phase2Id, tournamentId, 2),
                                buildPhase(phase3Id, tournamentId, 3)));

        assertThat(service.isLastPhase(phase1Id)).isFalse();
    }

    @Test
    @DisplayName(
            "isLastPhase returns false for a middle phase (sequenceNumber 2 in 3-phase tournament)")
    void isLastPhase_middleOfThree_returnsFalse() {
        UUID phase1Id = UUID.randomUUID();
        UUID phase2Id = UUID.randomUUID();
        UUID phase3Id = UUID.randomUUID();

        when(phaseRepository.findById(phase2Id))
                .thenReturn(Optional.of(buildPhase(phase2Id, tournamentId, 2)));
        when(phaseRepository.findByTournamentId(tournamentId))
                .thenReturn(
                        List.of(
                                buildPhase(phase1Id, tournamentId, 1),
                                buildPhase(phase2Id, tournamentId, 2),
                                buildPhase(phase3Id, tournamentId, 3)));

        assertThat(service.isLastPhase(phase2Id)).isFalse();
    }

    @Test
    @DisplayName("isLastPhase returns true for a single-phase tournament")
    void isLastPhase_singlePhase_returnsTrue() {
        UUID phaseId = UUID.randomUUID();

        when(phaseRepository.findById(phaseId))
                .thenReturn(Optional.of(buildPhase(phaseId, tournamentId, 1)));
        when(phaseRepository.findByTournamentId(tournamentId))
                .thenReturn(List.of(buildPhase(phaseId, tournamentId, 1)));

        assertThat(service.isLastPhase(phaseId)).isTrue();
    }

    @Test
    @DisplayName("isLastPhase throws IllegalArgumentException when phaseId is not found")
    void isLastPhase_phaseNotFound_throwsIAE() {
        UUID unknownPhaseId = UUID.randomUUID();

        when(phaseRepository.findById(unknownPhaseId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.isLastPhase(unknownPhaseId))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Phase not found: " + unknownPhaseId);
    }

    @Test
    @DisplayName("isLastPhase throws IllegalArgumentException when phaseId is null")
    void isLastPhase_nullPhaseId_throwsIAE() {
        assertThatThrownBy(() -> service.isLastPhase(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("phaseId");
    }

    // =========================================================================
    // AC-TEST-DRAFT-JSON-NULL-DEFENSIVE-RED — readGameMode
    // =========================================================================

    @Test
    @DisplayName("readGameMode throws IllegalStateException when draft_json is null")
    void readGameMode_draftJsonNull_throwsISE() {
        UUID tId = UUID.randomUUID();
        Tournament t = buildTournament(tId, null);
        when(tournamentRepository.findById(tId)).thenReturn(Optional.of(t));

        assertThatThrownBy(() -> impl.readGameMode(tId))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("no valid draft_json");
    }

    @Test
    @DisplayName("readGameMode throws IllegalStateException when draft_json is blank")
    void readGameMode_draftJsonBlank_throwsISE() {
        UUID tId = UUID.randomUUID();
        Tournament t = buildTournament(tId, "   ");
        when(tournamentRepository.findById(tId)).thenReturn(Optional.of(t));

        assertThatThrownBy(() -> impl.readGameMode(tId))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("no valid draft_json");
    }

    @Test
    @DisplayName(
            "readGameMode throws IllegalStateException when draft_json is syntactically invalid"
                    + " JSON")
    void readGameMode_invalidJson_throwsISE() {
        UUID tId = UUID.randomUUID();
        Tournament t = buildTournament(tId, "{not valid json!!!");
        when(tournamentRepository.findById(tId)).thenReturn(Optional.of(t));

        assertThatThrownBy(() -> impl.readGameMode(tId))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("no valid draft_json");
    }

    @Test
    @DisplayName("readGameMode throws IllegalArgumentException when tournament is not found")
    void readGameMode_tournamentNotFound_throwsIAE() {
        UUID tId = UUID.randomUUID();
        when(tournamentRepository.findById(tId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> impl.readGameMode(tId))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Tournament not found: " + tId);
    }

    @Test
    @DisplayName("readGameMode returns gameMode from valid draft_json")
    void readGameMode_validDraftJson_returnsGameMode() {
        UUID tId = UUID.randomUUID();
        String draftJson = "{\"sections\":[{\"sectionNumber\":1,\"gameMode\":\"awardCeremony\"}]}";
        Tournament t = buildTournament(tId, draftJson);
        when(tournamentRepository.findById(tId)).thenReturn(Optional.of(t));

        String gameMode = impl.readGameMode(tId);

        assertThat(gameMode).isEqualTo("awardCeremony");
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private Phase buildPhase(UUID phaseId, UUID tId, int sequenceNumber) {
        Phase p = new Phase();
        p.setId(phaseId);
        p.setTournamentId(tId);
        p.setSequenceNumber(sequenceNumber);
        p.setStatus("PENDING");
        return p;
    }

    private Tournament buildTournament(UUID tId, String draftJson) {
        Tournament t = new Tournament();
        t.setId(tId);
        t.setDraftJson(draftJson);
        return t;
    }
}
