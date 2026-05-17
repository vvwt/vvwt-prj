// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
package de.vvwt.tm.tournament.internal;

import de.vvwt.tm.tournament.Match;
import de.vvwt.tm.tournament.MatchFormat;
import de.vvwt.tm.tournament.MatchGenerator;
import de.vvwt.tm.tournament.MatchState;
import de.vvwt.tm.tournament.Phase;
import de.vvwt.tm.tournament.TeamAvatar;
import de.vvwt.tm.tournament.Tournament;
import de.vvwt.tm.tournament.TournamentRepository;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Round-robin match generator: every avatar plays every other avatar within its group exactly once
 * (E21S08; group-aware partitioning added by E51S09).
 *
 * <p>Reconstruction-in-place counterpart of {@code
 * de.vvwt.tm.domain.generator.RoundRobinMatchGenerator} (inventory row 258). Lives at {@code
 * de.vvwt.tm.tournament.internal} — the Modulith internal package per DEC-21 § Module layout. Uses
 * new {@code de.vvwt.tm.tournament.*} types.
 *
 * <p><b>Group-aware partitioning (E51S09, Bug 1 fix):</b> Input avatars are first partitioned by
 * {@code groupNumber} (DEC-9 structural identity). The circle-method ({@link #generatePairs}) is
 * invoked once per group with that group's avatar slice. Per-group match-lists are concatenated.
 * Total matches = ∑ over groups of {@code N_g * (N_g-1) / 2}. For 2 groups of 6 → 30 matches; for 3
 * groups of 4 → 18 matches. A group with fewer than 2 avatars produces 0 matches.
 *
 * <p><b>Single-group equivalence:</b> if all avatars share the same groupNumber, behaviour is
 * identical to the pre-E51S09 flat round-robin (AC-TEST-SINGLE-GROUP-PHASE-EQUIVALENCE).
 *
 * <p>The classic <em>circle method</em> generates all rounds deterministically per group. One team
 * is fixed at "top" while the others rotate clockwise — every pair appears exactly once.
 *
 * <h2>Bean qualifier</h2>
 *
 * <p>{@code @Component("roundRobin")} — renamed from {@code "roundRobin"} at E21S13 atomic cutover
 * after the legacy {@code @Component("roundRobin")} in {@code
 * de.vvwt.tm.domain.generator.RoundRobinMatchGenerator} was deleted (DEC-32 — mechanical
 * FQN-rewrite).
 *
 * @see MatchGenerator
 * @see de.vvwt.tm.tournament.MatchGeneratorRegistry
 * @see <a href="DEC-21">DEC-21 — Spring Modulith, internal package</a>
 * @see <a href="DEC-22">DEC-22 — TDD Iron Law (reconstruction-in-place)</a>
 * @see <a href="E21S08">E21S08 — inventory row 258</a>
 */
@Component("roundRobin")
public class RoundRobinMatchGenerator implements MatchGenerator {

    private static final Logger LOG = LoggerFactory.getLogger(RoundRobinMatchGenerator.class);

    /** Phantom UUID used internally for bye-round pairings. Never persisted. */
    private static final UUID PHANTOM_ID = new UUID(0L, 0L);

    private final TournamentRepository tournamentRepository;

    /**
     * Constructs the generator. Spring injects the tournament repository.
     *
     * @param tournamentRepository repository for looking up tournament match-format
     * @throws IllegalArgumentException if {@code tournamentRepository} is null
     */
    public RoundRobinMatchGenerator(TournamentRepository tournamentRepository) {
        if (tournamentRepository == null) {
            throw new IllegalArgumentException("tournamentRepository must not be null");
        }
        this.tournamentRepository = tournamentRepository;
    }

    /**
     * Returns the registry key {@code "roundRobin"}.
     *
     * <p>Renamed from {@code getBeanId()} by E58S01 (DEC-73 D-1).
     *
     * @return {@code "roundRobin"}
     */
    @Override
    public String getKeyId() {
        return "roundRobin";
    }

    /**
     * Returns {@code false} — Round-Robin is not the terminal phase generator.
     *
     * <p>Added by E58S01 (DEC-73 D-2).
     *
     * @return {@code false}
     */
    @Override
    public boolean isLastPhaseGenerator() {
        return false;
    }

    /**
     * {@inheritDoc}
     *
     * <p>Partitions {@code avatars} by {@link TeamAvatar#getGroupNumber()} (DEC-9), then generates
     * a full intra-group round-robin for each partition using the circle method ({@link
     * #generatePairs}). Match-lists from all groups are concatenated. {@code lapNumber} and {@code
     * fieldNumber} are {@code null} on every produced {@link Match} — coordinate assignment is L2's
     * responsibility (DEC-55 D-3 step 2, E51S10). Returns an immutable list.
     *
     * @param phase the phase for which matches are generated; must not be {@code null}
     * @param avatars the full list of phase avatars; must not be {@code null} or contain {@code
     *     null} entries; no duplicate IDs permitted
     * @return immutable list of generated matches (possibly empty if fewer than 2 avatars total)
     */
    @Override
    public List<Match> generate(Phase phase, List<TeamAvatar> avatars) {
        if (phase == null) {
            throw new IllegalArgumentException("phase must not be null");
        }
        if (avatars == null) {
            throw new IllegalArgumentException("avatars must not be null");
        }
        for (int i = 0; i < avatars.size(); i++) {
            if (avatars.get(i) == null) {
                throw new IllegalArgumentException(
                        "avatars list contains a null entry at index " + i);
            }
        }
        Set<UUID> seen = new HashSet<>();
        for (TeamAvatar avatar : avatars) {
            if (!seen.add(avatar.getId())) {
                throw new IllegalArgumentException(
                        "Duplicate avatar ID in input list: " + avatar.getId());
            }
        }

        if (avatars.isEmpty()) {
            LOG.info("[roundRobin] generate: avatarCount=0, matches=0 (empty input)");
            return Collections.emptyList();
        }

        UUID tournamentId = phase.getTournamentId();
        Optional<Tournament> tournamentOpt = tournamentRepository.findById(tournamentId);
        if (tournamentOpt.isEmpty()) {
            throw new IllegalStateException(
                    "Tournament not found for id="
                            + tournamentId
                            + " (referenced by phase "
                            + phase.getId()
                            + ")");
        }
        Tournament tournament = tournamentOpt.get();
        int setLimit = MatchFormat.fromPersistedName(tournament.getMatchFormat()).getMaxSets();

        // E51S09 — partition avatars by groupNumber (DEC-9 structural identity).
        // Use LinkedHashMap to preserve insertion order across groups (deterministic output).
        Map<Integer, List<TeamAvatar>> byGroup = new LinkedHashMap<>();
        for (TeamAvatar avatar : avatars) {
            byGroup.computeIfAbsent(avatar.getGroupNumber(), k -> new ArrayList<>()).add(avatar);
        }

        LocalDateTime now = LocalDateTime.now();
        List<Match> matches = new ArrayList<>();

        for (Map.Entry<Integer, List<TeamAvatar>> entry : byGroup.entrySet()) {
            int groupNumber = entry.getKey();
            List<TeamAvatar> groupAvatars = entry.getValue();

            if (groupAvatars.size() < 2) {
                LOG.info(
                        "[roundRobin] generate: group={}, avatarCount={} — skipped (< 2 avatars,"
                                + " no matches possible)",
                        groupNumber,
                        groupAvatars.size());
                continue;
            }

            List<UUID[]> pairs = generatePairs(groupAvatars);
            for (UUID[] pair : pairs) {
                Match match =
                        new Match(
                                UUID.randomUUID(),
                                phase.getTournamentId(),
                                phase.getId(),
                                pair[0],
                                pair[1],
                                MatchState.OPEN.getLegacyCode(),
                                setLimit,
                                null, // lapNumber — null per DEC-55 D-3 step 2 (L2 assigns)
                                null, // fieldNumber — null per DEC-55 D-3 step 2 (L2 assigns)
                                null,
                                null,
                                null,
                                now);
                matches.add(match);
                LOG.debug("[roundRobin] group={} pairing: {} vs {}", groupNumber, pair[0], pair[1]);
            }

            LOG.info(
                    "[roundRobin] generate: group={}, groupSize={}, matchesGenerated={}",
                    groupNumber,
                    groupAvatars.size(),
                    pairs.size());
        }

        LOG.info(
                "[roundRobin] generate: totalAvatars={}, groups={}, totalMatchesGenerated={}",
                avatars.size(),
                byGroup.size(),
                matches.size());

        return Collections.unmodifiableList(matches);
    }

    /**
     * Core round-robin pairing algorithm (circle method).
     *
     * <p>For N avatars, produces all {@code N*(N-1)/2} pairs. For odd N, a phantom entry is added
     * to make N even; pairings involving the phantom are dropped.
     */
    List<UUID[]> generatePairs(List<TeamAvatar> avatars) {
        int n = avatars.size();
        boolean addPhantom = (n % 2 != 0);
        int slots = addPhantom ? n + 1 : n;
        int rounds = slots - 1;

        UUID[] slot = new UUID[slots];
        for (int i = 0; i < n; i++) {
            slot[i] = avatars.get(i).getId();
        }
        if (addPhantom) {
            slot[n] = PHANTOM_ID;
        }

        List<UUID[]> pairs = new ArrayList<>(n * (n - 1) / 2);

        for (int round = 0; round < rounds; round++) {
            for (int i = 0; i < slots / 2; i++) {
                UUID a = slot[i];
                UUID b = slot[slots - 1 - i];
                if (!PHANTOM_ID.equals(a) && !PHANTOM_ID.equals(b)) {
                    pairs.add(new UUID[] {a, b});
                }
            }
            // Rotate slots 1..slots-1 one position clockwise
            UUID last = slot[slots - 1];
            System.arraycopy(slot, 1, slot, 2, slots - 2);
            slot[1] = last;
        }

        return pairs;
    }
}
