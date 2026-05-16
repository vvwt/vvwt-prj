// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
package de.vvwt.tm.tournament.draft;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

/**
 * Draft configuration for tournament phase-planning — an ordered list of sections.
 *
 * <p>Each section defines the parameters for one Phase that will be created when the draft is
 * applied (AC-TDD-DraftConfig). Immutable. Jackson deserializes via the {@link
 * JsonCreator}-annotated constructor.
 *
 * <p>Inventory: E21S01 line 238. Named-interface sub-package placement by E33S04 (DEC-35 retrofit).
 * Legacy {@code de.vvwt.tm.domain.draft.DraftConfig} remains active until E21S13 atomic cutover
 * (DEC-32).
 *
 * @see DraftSection
 * @see <a href="DEC-21">DEC-21 — Spring Modulith package layout</a>
 * @see <a href="DEC-22">DEC-22 — TDD reconstruction-in-place</a>
 * @see <a href="E21S07">E21S07 — Draft phase-planning reconstruction</a>
 * @see <a href="E48S01">E48S01 — last-phase-awardCeremony invariant (D-10)</a>
 * @see <a href="E48S16">E48S16 — first-phase sortType=team_number invariant</a>
 * @see <a href="E58S01">E58S01 — AC5 GameMode enum removed; validateLastPhaseAwardCeremony uses
 *     String comparison</a>
 * @see <a href="E58S04">E58S04 — renamed siegerehrung → awardCeremony (DEC-73 D-7)</a>
 */
public final class DraftConfig {

    /** Ordered list of sections. May be empty. */
    private final List<DraftSection> sections;

    /**
     * Jackson-compatible constructor.
     *
     * @param sections ordered list of draft sections; {@code null} treated as empty
     */
    @JsonCreator
    public DraftConfig(@JsonProperty("sections") List<DraftSection> sections) {
        this.sections = sections == null ? List.of() : List.copyOf(sections);
    }

    /**
     * Returns an immutable ordered list of sections.
     *
     * @return sections; never {@code null}; may be empty
     */
    public List<DraftSection> getSections() {
        return sections;
    }

    /**
     * Factory: empty draft (no sections configured yet).
     *
     * @return a {@code DraftConfig} with no sections
     */
    public static DraftConfig empty() {
        return new DraftConfig(Collections.emptyList());
    }

    /**
     * Validates that the first phase (lowest {@code sectionNumber}) has {@code
     * sortType=team_number}, as required by the E48S16 invariant.
     *
     * <p>Phase 1 has no predecessor; only {@code team_number} makes sense for the initial
     * round-robin distribution. {@code placement_group} and {@code group_placement} require
     * TeamAvatar ratings from a previous phase, which Phase 1 does not have.
     *
     * <p>No-op if there are no sections (empty draft has nothing to enforce).
     *
     * @throws IllegalArgumentException if the first phase does not have {@code
     *     sortType=team_number}; the message identifies the offending sectionNumber and its actual
     *     sortType
     * @see <a href="E48S16">E48S16 — first-phase sortType=team_number invariant</a>
     */
    public void validateFirstPhaseTeamNumber() {
        if (sections.isEmpty()) {
            return;
        }
        DraftSection firstSection =
                sections.stream()
                        .min(Comparator.comparingInt(DraftSection::getSectionNumber))
                        .orElseThrow();
        if (!"team_number".equals(firstSection.getSortType())) {
            throw new IllegalArgumentException(
                    "First phase (sectionNumber "
                            + firstSection.getSectionNumber()
                            + ") must have sortType=team_number, got: "
                            + firstSection.getSortType());
        }
    }

    /**
     * Validates that the last phase (highest {@code sectionNumber}) has {@code
     * gameMode=awardCeremony}, as required by the D-10 invariant.
     *
     * <p>No-op if there are no sections (empty draft has nothing to enforce).
     *
     * @throws IllegalArgumentException if the last phase does not have {@code
     *     gameMode=awardCeremony}; the message identifies the offending sectionNumber and its
     *     actual gameMode
     * @see <a href="E48S01">E48S01 — last-phase-awardCeremony invariant (D-10)</a>
     */
    public void validateLastPhaseAwardCeremony() {
        if (sections.isEmpty()) {
            return;
        }
        DraftSection lastSection =
                sections.stream()
                        .max(Comparator.comparingInt(DraftSection::getSectionNumber))
                        .orElseThrow();
        // E58S04 DEC-73 D-7: renamed from siegerehrung to awardCeremony
        // E58S01 DEC-73 D-5: gameMode is a String; use "awardCeremony".equals() (null-safe,
        // per DEC-59 string comparison convention — constant on left)
        if (!"awardCeremony".equals(lastSection.getGameMode())) {
            throw new IllegalArgumentException(
                    "Last phase (sectionNumber "
                            + lastSection.getSectionNumber()
                            + ") must have gameMode=awardCeremony, got: "
                            + lastSection.getGameMode());
        }
    }

    /**
     * Validates that all sections have a {@code gameMode} that is a member of the given set of
     * known registry keys (AC6, E58S01).
     *
     * <p>No-op if there are no sections or if {@code knownIds} is empty.
     *
     * @param knownIds the set of registered generator key IDs; must not be {@code null}
     * @throws IllegalArgumentException if any section has a {@code gameMode} not in {@code
     *     knownIds}; the message names the unknown key
     * @see <a href="E58S01">E58S01 — AC6 registry-membership validation</a>
     * @see <a href="DEC-73">DEC-73 D-7</a>
     */
    public void validateGameModeMembership(Set<String> knownIds) {
        for (DraftSection section : sections) {
            String gm = section.getGameMode();
            if (!knownIds.contains(gm)) {
                throw new IllegalArgumentException(
                        "Section "
                                + section.getSectionNumber()
                                + ": gameMode '"
                                + gm
                                + "' is not registered in the MatchGeneratorRegistry."
                                + " Known ids: "
                                + String.join(", ", new java.util.TreeSet<>(knownIds))
                                + " (AC6, E58S01)");
            }
        }
    }

    /**
     * Validates that all sections have a {@code distributionMode} that is a member of the given set
     * of known registry keys (AC6, E58S02).
     *
     * <p>No-op if there are no sections or if {@code knownKeys} is empty.
     *
     * @param knownKeys the set of registered distributor key IDs; must not be {@code null}
     * @throws IllegalArgumentException if any section has a {@code distributionMode} not in {@code
     *     knownKeys}; the message names the unknown key
     * @see <a href="E58S02">E58S02 — AC6 distributionMode registry-membership validation</a>
     * @see <a href="DEC-73">DEC-73 D-2</a>
     */
    public void validateDistributionModeMembership(Set<String> knownKeys) {
        for (DraftSection section : sections) {
            String dm = section.getDistributionMode();
            if (!knownKeys.contains(dm)) {
                throw new IllegalArgumentException(
                        "Section "
                                + section.getSectionNumber()
                                + ": distributionMode '"
                                + dm
                                + "' is not registered in the Team2AvatarDistributorRegistry."
                                + " Known keys: "
                                + String.join(", ", new java.util.TreeSet<>(knownKeys))
                                + " (AC6, E58S02)");
            }
        }
    }

    /**
     * Validates that all sections have a {@code sortType} that is a member of the given set of
     * known registry keys (AC6, E58S03).
     *
     * <p>Replaces the hardcoded string-set check that previously lived in {@link
     * DraftSection#validate()} — the registry is the single source of truth for valid sort keys.
     *
     * @param knownKeys the set of registered sort-calculator key IDs; must not be {@code null}
     * @throws IllegalArgumentException if any section has a {@code sortType} not in {@code
     *     knownKeys}; the message names the unknown key
     * @see <a href="E58S03">E58S03 — AC6 sortType registry-membership validation</a>
     * @see <a href="DEC-73">DEC-73 D-3</a>
     */
    public void validateSortTypeMembership(Set<String> knownKeys) {
        for (DraftSection section : sections) {
            String st = section.getSortType();
            if (st != null && !knownKeys.contains(st)) {
                throw new IllegalArgumentException(
                        "Section "
                                + section.getSectionNumber()
                                + ": sortType '"
                                + st
                                + "' is not registered in the TeamSortCalculatorRegistry."
                                + " Known keys: "
                                + String.join(", ", new java.util.TreeSet<>(knownKeys))
                                + " (AC6, E58S03)");
            }
        }
    }
}
