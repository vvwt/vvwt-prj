// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
package de.vvwt.tm.tournament;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * RED-first tests for E51S13 — {@link TeamAvatarProposal} sortType field and {@link
 * de.vvwt.tm.tournament.internal.DefaultPhaseTransitionService} sortType population.
 *
 * <p>DEC-22 Iron Law: all tests written before the {@code sortType} field exists → RED first.
 *
 * <p>DEC-36: cross-package test (de.vvwt.tm.tournament) — references public API only.
 *
 * <p>Covers:
 *
 * <ul>
 *   <li>AC-TEST-DTO-SORTTYPE-FIELD-EXISTS-RED — {@link TeamAvatarProposal} has 8 fields including
 *       {@code sortType}.
 *   <li>AC-IMPL-DTO-SORTTYPE-NULLABLE — {@code sortType} is {@link String} (nullable); {@link
 *       TeamAvatarProposal#forCommit} sets it to {@code null}.
 *   <li>AC-IMPL-DEC-9-NO-UUID-IN-DOM — {@code sortType} is a domain string, not a UUID.
 * </ul>
 *
 * @see TeamAvatarProposal
 * @see <a href="DEC-22">DEC-22 — TDD Iron Law (RED-first)</a>
 * @see <a href="DEC-9">DEC-9 — TeamAvatar structural identity; UUIDs must not surface in UI</a>
 * @see <a href="E51S13">E51S13 — Bug 2a sortType-driven source-pane label</a>
 */
@DisplayName("TeamAvatarProposal sortType field — E51S13 RED-first")
class TeamAvatarProposalSortTypeTest {

    /**
     * AC-TEST-DTO-SORTTYPE-FIELD-EXISTS-RED: {@link TeamAvatarProposal} record has exactly 8
     * components (the 7 existing ones + {@code sortType}).
     *
     * <p>Verified via reflection so the test is self-contained and does not require compilation of
     * the new field. Before the fix, the record has 7 components → assertion fails (RED).
     */
    @Test
    @DisplayName(
            "TeamAvatarProposal has 8 record components including sortType"
                    + " (AC-TEST-DTO-SORTTYPE-FIELD-EXISTS-RED)")
    void teamAvatarProposal_has8Components_includingSortType() {
        RecordComponent[] components = TeamAvatarProposal.class.getRecordComponents();
        assertThat(components)
                .as(
                        "TeamAvatarProposal must have exactly 8 record components (7 existing +"
                                + " sortType)")
                .hasSize(8);
        String[] names =
                Arrays.stream(components).map(RecordComponent::getName).toArray(String[]::new);
        assertThat(names)
                .as("TeamAvatarProposal must contain a 'sortType' component")
                .contains("sortType");
    }

    /**
     * AC-IMPL-DTO-SORTTYPE-NULLABLE: the {@code sortType} component type is {@link String}
     * (nullable on the wire; forCommit sets it to null).
     *
     * <p>DEC-9 note: sortType must be a domain String value (e.g., "team_number"), NOT a UUID —
     * this test verifies the component type is String.
     */
    @Test
    @DisplayName(
            "TeamAvatarProposal.sortType component type is String (AC-IMPL-DTO-SORTTYPE-NULLABLE,"
                    + " DEC-9)")
    void teamAvatarProposal_sortType_isStringType() {
        RecordComponent sortTypeComponent =
                Arrays.stream(TeamAvatarProposal.class.getRecordComponents())
                        .filter(c -> "sortType".equals(c.getName()))
                        .findFirst()
                        .orElseThrow(
                                () ->
                                        new AssertionError(
                                                "sortType component not found in"
                                                        + " TeamAvatarProposal"));
        assertThat(sortTypeComponent.getType())
                .as(
                        "sortType must be of type String (not UUID — per DEC-9 no-UUID-in-DOM"
                                + " invariant)")
                .isEqualTo(String.class);
    }

    /**
     * AC-IMPL-DTO-SORTTYPE-NULLABLE (forCommit path): {@link TeamAvatarProposal#forCommit} must
     * produce a proposal with {@code sortType = null} (commit-path does not need sortType — it is
     * only relevant for the proposal/review phase).
     */
    @Test
    @DisplayName("TeamAvatarProposal.forCommit sets sortType=null (AC-IMPL-DTO-SORTTYPE-NULLABLE)")
    void teamAvatarProposal_forCommit_sortTypeIsNull() {
        UUID teamId = UUID.randomUUID();
        TeamAvatarProposal proposal = TeamAvatarProposal.forCommit(teamId, 1, 2);
        // Reflective access since sortType does not yet exist — once field exists, accessor works
        assertThat(proposal.sortType())
                .as("forCommit must set sortType=null (not needed for commit path)")
                .isNull();
    }
}
