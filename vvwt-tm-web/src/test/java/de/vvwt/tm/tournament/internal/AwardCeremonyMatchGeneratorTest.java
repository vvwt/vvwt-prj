// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
package de.vvwt.tm.tournament.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.vvwt.tm.tournament.Match;
import de.vvwt.tm.tournament.MatchGenerator;
import de.vvwt.tm.tournament.Phase;
import de.vvwt.tm.tournament.TeamAvatar;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link AwardCeremonyMatchGenerator} — AC-TEST-AWARD-CEREMONY-GENERATOR-RED.
 *
 * <p>RED-first per DEC-22 Iron Law: tests were written before the production class existed
 * (originally as {@code SiegerehrungMatchGeneratorTest}; renamed by E58S04 — DEC-73 D-7).
 *
 * <h2>DEC-36 same-package test typing</h2>
 *
 * <p>This test class resides in {@code de.vvwt.tm.tournament.internal} — the SAME package as {@link
 * AwardCeremonyMatchGenerator}. Per DEC-36, same-package tests MAY reference the implementation
 * class directly (white-box). The test exercises both the {@link MatchGenerator} interface contract
 * and the implementation-specific no-op behaviour.
 *
 * @see AwardCeremonyMatchGenerator
 * @see MatchGenerator
 * @see <a href="DEC-22">DEC-22 — TDD Iron Law</a>
 * @see <a href="DEC-36">DEC-36 — cross-package test typing (same-package: white-box permitted)</a>
 * @see <a href="DEC-73">DEC-73 — D-7: siegerehrung → awardCeremony rename</a>
 */
@DisplayName("AwardCeremonyMatchGenerator — unit tests (AC-TEST-AWARD-CEREMONY-GENERATOR-RED)")
class AwardCeremonyMatchGeneratorTest {

    private AwardCeremonyMatchGenerator generator;

    @BeforeEach
    void setUp() {
        generator = new AwardCeremonyMatchGenerator();
    }

    // -------------------------------------------------------------------------
    // AC3 (E58S04) — getKeyId() returns "awardCeremony"
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("getKeyId returns 'awardCeremony' (AC3, E58S04 / DEC-73 D-7)")
    void getKeyId_returnsAwardCeremony() {
        assertThat(generator.getKeyId()).isEqualTo("awardCeremony");
    }

    // -------------------------------------------------------------------------
    // AC2 (E58S01) — isLastPhaseGenerator() returns true
    // -------------------------------------------------------------------------

    @Test
    @DisplayName(
            "isLastPhaseGenerator returns true — awardCeremony is the terminal phase (AC2, E58S01)")
    void isLastPhaseGenerator_returnsTrue() {
        assertThat(generator.isLastPhaseGenerator()).isTrue();
    }

    // -------------------------------------------------------------------------
    // generate returns empty list (no-op)
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("generate returns empty list for two avatars (no-op semantics)")
    void generate_returnsEmptyList() {
        Phase phase = buildPhase();
        List<TeamAvatar> avatars = List.of(buildAvatar(), buildAvatar());

        List<Match> result = generator.generate(phase, avatars);

        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("generate returns empty list even for zero avatars")
    void generate_zeroAvatars_returnsEmptyList() {
        Phase phase = buildPhase();

        List<Match> result = generator.generate(phase, List.of());

        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("generate returns unmodifiable list")
    void generate_returnsUnmodifiableList() {
        Phase phase = buildPhase();
        List<TeamAvatar> avatars = List.of(buildAvatar());

        List<Match> result = generator.generate(phase, avatars);

        assertThatThrownBy(() -> result.add(null))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    // -------------------------------------------------------------------------
    // Input validation — null guards per MatchGenerator contract
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("generate throws IAE when phase is null")
    void generate_nullPhase_throwsIAE() {
        assertThatThrownBy(() -> generator.generate(null, List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("phase");
    }

    @Test
    @DisplayName("generate throws IAE when avatars is null")
    void generate_nullAvatars_throwsIAE() {
        Phase phase = buildPhase();
        assertThatThrownBy(() -> generator.generate(phase, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("avatars");
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private Phase buildPhase() {
        Phase p = new Phase();
        p.setId(UUID.randomUUID());
        p.setTournamentId(UUID.randomUUID());
        p.setSequenceNumber(1);
        p.setStatus("PENDING");
        return p;
    }

    private TeamAvatar buildAvatar() {
        TeamAvatar a = new TeamAvatar();
        a.setId(UUID.randomUUID());
        return a;
    }
}
