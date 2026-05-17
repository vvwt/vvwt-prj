// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
package de.vvwt.tm.tournament.internal;

import de.vvwt.tm.tournament.Match;
import de.vvwt.tm.tournament.MatchRepository;
import de.vvwt.tm.tournament.RoundAssignmentService;
import de.vvwt.tm.tournament.TeamAvatar;
import de.vvwt.tm.tournament.TeamAvatarRepository;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Default L2 Round-Assignment implementation using a phase-global voting-driven greedy slot-filling
 * algorithm (DEC-61 Clause A).
 *
 * <h2>Algorithm (voting-driven phase-global — DEC-61 Clause A)</h2>
 *
 * <p>Ports {@code de.vvwerratal.vvw.tournaments.services.match.MatchDistributor
 * .createMatchListForTournament} (lines 332-425) from the legacy vvw-tournaments-services module to
 * vvwt-prj types (per user mandate 2026-05-10).
 *
 * <ol>
 *   <li>Load all matches for the phase from {@link MatchRepository} (L1 output, flat list).
 *   <li>Load all avatars from {@link TeamAvatarRepository} to build the avatar-to-groupNumber
 *       index.
 *   <li>Build three vote-tracking structures (all start at zero):
 *       <ul>
 *         <li>{@link AvatarVoting} per avatar UUID — incremented every time the avatar is assigned.
 *         <li>{@link GroupVoting} per groupNumber — incremented every time any avatar in the group
 *             is assigned.
 *       </ul>
 *   <li>Wrap each match in a {@link VotedMatch} linking it to its two avatar votings and its group
 *       voting.
 *   <li>For {@code lap = 1, 2, 3, …} while unassigned matches remain:
 *       <ol type="a">
 *         <li>Sort {@code votedMatches} ascending by combined vote ({@code avatar1Voting +
 *             avatar2Voting + groupVoting}).
 *         <li>Track avatars already playing in this lap ({@code Set<UUID> lapUsed}).
 *         <li>For {@code field = 1..fieldCount}: scan sorted matches for the first conflict-free
 *             candidate (neither avatar in {@code lapUsed}). If found: assign {@code lapNumber=lap,
 *             fieldNumber=field} (1-based per DEC-60 D-1); increment all three vote counters; add
 *             both avatars to {@code lapUsed}; remove the match from the unassigned list. If NOT
 *             found: Bye-Slot — no Match-Row written for this {@code (lap, field)} position.
 *       </ol>
 *   <li>Persist all matches with {@code lapNumber}/{@code fieldNumber} set via {@link
 *       MatchRepository#save(Match)}.
 * </ol>
 *
 * <h2>Voting-Triple semantics</h2>
 *
 * <p>{@code combinedVote(m) = avatar1Voting(m) + avatar2Voting(m) + groupVoting(m)}. The match with
 * the <em>lowest</em> combined vote is selected first. Ties are resolved by the stable sort of
 * {@link ArrayList#sort} (insertion order of {@code votedMatches} breaks ties).
 *
 * <h2>Conflict-freedom</h2>
 *
 * <p>Within any single lap, no avatar appears in two matches ({@link #assignRoundsAndFields(UUID,
 * int)} guarantees this by construction via {@code lapUsed}). Field-count capacity is guaranteed by
 * the {@code field = 1..fieldCount} loop: at most {@code fieldCount} matches are assigned per lap.
 *
 * <h2>Bye-Slots (DEC-61 Clause C)</h2>
 *
 * <p>Asymmetric phase configurations (e.g., 11 teams in 2 groups or {@code fieldCount} exceeding
 * per-lap capacity) may produce Bye-Slot positions where no conflict-free match exists. Bye-Slots
 * produce no Match-Row. Display surfaces (post-E50S04/E53S08) render missing {@code (lap, field)}
 * cells as Spielfrei.
 *
 * <h2>1-based lap + field (DEC-60 D-1 preservation)</h2>
 *
 * <p>The outer lap loop starts at {@code 1}; the inner field loop starts at {@code 1}. Both are
 * directly assigned as {@code lapNumber} and {@code fieldNumber}. No 0-based intermediate.
 *
 * <h2>Inner-class exemption from DEC-58 interface mandate</h2>
 *
 * <p>{@link AvatarVoting}, {@link GroupVoting}, and {@link VotedMatch} are {@code private static}
 * data carriers, NOT Spring-managed beans ({@code @Service}/{@code @Component}). They are exempt
 * from the DEC-58 universal-interface mandate per DEC-61 Clause G.
 *
 * <h2>Spielart-agnostic guarantee</h2>
 *
 * <p>This class does NOT import or reference {@code RoundRobinMatchGenerator}, {@code
 * SiegerehrungMatchGenerator}, or any {@code Spielart}/{@code gameMode} string. It operates
 * exclusively on {@link Match} objects and repositories.
 *
 * <h2>B-b1 cycle-break (E51S16)</h2>
 *
 * <p>No {@code PhaseToRawPhaseDefMapper} dependency (removed in E51S16). Field-count comes from the
 * caller ({@code MatchGenJobExecutor}) via the method parameter.
 *
 * <h2>Coherence anchor</h2>
 *
 * <p>{@link #fallbackFieldCountCoherenceAnchor} is NOT used at runtime — it is a drift guard for
 * {@code FieldCountDefaultCoherenceTest} (AC-IMPL-DEFAULT-FIELDCOUNT-COHERENCE) per E51S16.
 *
 * @see RoundAssignmentService
 * @see <a href="DEC-9">DEC-9 — TeamAvatar structural identity</a>
 * @see <a href="DEC-21">DEC-21 — Spring Modulith; tournament allowedDependencies = tenant only</a>
 * @see <a href="DEC-22">DEC-22 — TDD Iron Law (RED-first)</a>
 * @see <a href="DEC-35">DEC-35 — Spring Modulith: impl in .internal</a>
 * @see <a href="DEC-37">DEC-37 Clause B — runs within caller's TX (no own @Transactional)</a>
 * @see <a href="DEC-55">DEC-55 D-3 — L2 writes lap+field after L1</a>
 * @see <a href="DEC-58">DEC-58 — Universal interface mandate (inner-class exemption applies)</a>
 * @see <a href="DEC-60">DEC-60 D-1 — 1-based lapNumber + fieldNumber write convention</a>
 * @see <a href="DEC-61">DEC-61 Clause A — L2 voting-driven phase-global slot-filling</a>
 * @see <a href="E51S10">E51S10 — L2 Round-Assignment Service story</a>
 * @see <a href="E51S16">E51S16 — B-b1 Modulith-cycle elimination</a>
 * @see <a href="E54S01">E54S01 — L2 voting-driven port (DEC-61 operationalization)</a>
 */
@Service
class DefaultRoundAssignmentService implements RoundAssignmentService {

    private static final Logger LOG = LoggerFactory.getLogger(DefaultRoundAssignmentService.class);

    private final MatchRepository matchRepository;
    private final TeamAvatarRepository teamAvatarRepository;

    /**
     * Coherence anchor: the configured fallback field-count default.
     *
     * <p>This field is NOT used at runtime for round-assignment logic — {@link
     * #assignRoundsAndFields(UUID, int)} receives the already-resolved {@code fieldCount} from
     * {@code MatchGenJobExecutor}. Its presence here serves as a drift guard: if this literal ever
     * diverges from the literal in {@code MatchGenJobExecutor} or {@code PhaseToRawPhaseDefMapper},
     * {@code FieldCountDefaultCoherenceTest} fails immediately
     * (AC-IMPL-DEFAULT-FIELDCOUNT-COHERENCE, AC-TEST-DEFAULT-FIELDCOUNT-COHERENCE-RED).
     *
     * <p>The literal MUST be EXACTLY {@code ${tm.slotopt.fallback.field-count:3}} —
     * character-identical to {@code MatchGenJobExecutor} and {@code PhaseToRawPhaseDefMapper}.
     */
    @Value("${tm.slotopt.fallback.field-count:3}")
    private int fallbackFieldCountCoherenceAnchor;

    DefaultRoundAssignmentService(
            MatchRepository matchRepository, TeamAvatarRepository teamAvatarRepository) {
        this.matchRepository = matchRepository;
        this.teamAvatarRepository = teamAvatarRepository;
    }

    /**
     * {@inheritDoc}
     *
     * <p>Implements phase-global voting-driven greedy slot-filling per DEC-61 Clause A. Replaces
     * the previous per-group greedy edge-coloring + cumulative-lap-offset concatenation.
     *
     * <p>Worked example (fieldCount=3, 12 teams in 2 groups of 6 → 30 matches, 10 laps):
     *
     * <pre>
     * All 30 matches start with vote=0. Sorted ascending by combined vote = 0 for all.
     * Lap 1, field 1: first conflict-free match → assigned; both avatars' and group vote++.
     * Lap 1, field 2: next conflict-free match (different avatars) → assigned; votes++.
     * Lap 1, field 3: next conflict-free match → assigned; votes++.
     * Lap 2: matches with lowest vote (those with avatars not yet assigned) selected first.
     * ...
     * Result: 10 laps × 3 matches each; avatars from Group 1 and Group 2 interleaved.
     * </pre>
     *
     * @param phaseId must not be {@code null}
     * @param fieldCount must be ≥ 1
     */
    @Override
    public void assignRoundsAndFields(UUID phaseId, int fieldCount) {
        if (phaseId == null) {
            throw new IllegalArgumentException("phaseId must not be null");
        }
        if (fieldCount < 1) {
            throw new IllegalArgumentException(
                    "fieldCount must be ≥ 1 after fallback resolution; resolved="
                            + fieldCount
                            + ". Set tournament.fieldCount ≥ 1 or"
                            + " tm.slotopt.fallback.field-count ≥ 1 in application config.");
        }

        List<Match> matches = matchRepository.findByPhaseId(phaseId);

        if (matches.isEmpty()) {
            LOG.info(
                    "DefaultRoundAssignmentService: phase={} has 0 matches — no-op"
                            + " (siegerehrung or empty phase)",
                    phaseId);
            return;
        }

        // ── Step 1: Build avatar-to-groupNumber index ──────────────────────────────────────────
        List<TeamAvatar> avatars = teamAvatarRepository.findByPhaseId(phaseId);
        Map<UUID, Integer> avatarGroupNumber = new HashMap<>(avatars.size() * 2);
        for (TeamAvatar avatar : avatars) {
            avatarGroupNumber.put(avatar.getId(), avatar.getGroupNumber());
        }

        // ── Step 2: Build voting structures ───────────────────────────────────────────────────
        // AvatarVoting: one counter per avatar UUID; incremented on each assignment.
        // GroupVoting: one counter per groupNumber; incremented on each assignment in that group.
        // Initial vote = 0 for all (highest scheduling priority).
        Map<UUID, AvatarVoting> avatarVotings = new HashMap<>(avatars.size() * 2);
        for (TeamAvatar avatar : avatars) {
            avatarVotings.put(avatar.getId(), new AvatarVoting());
        }
        Map<Integer, GroupVoting> groupVotings = new HashMap<>();

        // ── Step 3: Build VotedMatch list ─────────────────────────────────────────────────────
        // Each match is wrapped with references to its two avatar votings and its group voting.
        List<VotedMatch> votedMatches = new ArrayList<>(matches.size());
        for (Match match : matches) {
            UUID av1Id = match.getMemberAvatar1Id();
            UUID av2Id = match.getMemberAvatar2Id();
            int groupNumber = avatarGroupNumber.getOrDefault(av1Id, 1);

            AvatarVoting av1Voting = avatarVotings.computeIfAbsent(av1Id, k -> new AvatarVoting());
            AvatarVoting av2Voting = avatarVotings.computeIfAbsent(av2Id, k -> new AvatarVoting());
            GroupVoting grpVoting =
                    groupVotings.computeIfAbsent(groupNumber, k -> new GroupVoting());

            votedMatches.add(new VotedMatch(match, av1Voting, av2Voting, grpVoting));
        }

        // ── Step 4: Phase-global voting-driven slot-filling (DEC-61 Clause A) ─────────────────
        // Outer loop: iterate laps starting at 1 (1-based per DEC-60 D-1).
        // Inner loop: iterate fields 1..fieldCount within each lap.
        // Exit: when all matches have been assigned (votedMatches is empty).
        int lap = 0;
        while (!votedMatches.isEmpty()) {
            lap++;

            // Sort ascending by combined vote: lowest vote wins the next slot.
            // ArrayList.sort is stable — insertion order resolves ties.
            votedMatches.sort(Comparator.comparingInt(VotedMatch::combinedVote));

            // Track avatars already assigned a match in this lap.
            Set<UUID> lapUsed = new HashSet<>();

            for (int field = 1; field <= fieldCount; field++) {
                if (votedMatches.isEmpty()) {
                    break; // all matches consumed mid-lap; remaining fields are Bye-Slots
                }

                // Find the first conflict-free match (neither avatar already in lapUsed).
                int selectedIndex = -1;
                for (int i = 0; i < votedMatches.size(); i++) {
                    VotedMatch candidate = votedMatches.get(i);
                    UUID a1 = candidate.match.getMemberAvatar1Id();
                    UUID a2 = candidate.match.getMemberAvatar2Id();
                    if (!lapUsed.contains(a1) && !lapUsed.contains(a2)) {
                        selectedIndex = i;
                        break;
                    }
                }

                if (selectedIndex == -1) {
                    // Bye-Slot: no conflict-free match exists for this (lap, field).
                    // Per DEC-61 Clause C: no Match-Row is created. Continue to next field.
                    continue;
                }

                // Assign the selected match to (lap, field) — 1-based per DEC-60 D-1.
                VotedMatch selected = votedMatches.remove(selectedIndex);
                selected.match.setLapNumber(lap);
                selected.match.setFieldNumber(field);

                // Increment vote counters for this assignment.
                selected.avatar1Voting.increment();
                selected.avatar2Voting.increment();
                selected.groupVoting.increment();

                // Mark both avatars as used in this lap.
                lapUsed.add(selected.match.getMemberAvatar1Id());
                lapUsed.add(selected.match.getMemberAvatar2Id());
            }
        }

        // ── Step 5: Persist all lap+field assignments ──────────────────────────────────────────
        // Runs within the caller's REQUIRES_NEW TX (MatchGenJobExecutor) — atomicity guaranteed.
        for (Match match : matches) {
            matchRepository.save(match);
        }

        LOG.info(
                "DefaultRoundAssignmentService: phase={}, totalMatches={}, fieldCount={},"
                        + " lapsUsed={} (voting-driven phase-global DEC-61)",
                phaseId,
                matches.size(),
                fieldCount,
                lap);
    }

    // ══════════════════════════════════════════════════════════════════════════════════════════
    // Private static inner classes — DEC-58 exemption (non-Spring-managed data carriers)
    // DEC-61 Clause G: exempt from universal-interface mandate as private static inner types.
    // ══════════════════════════════════════════════════════════════════════════════════════════

    /**
     * Vote counter for a single avatar. Incremented every time the avatar is assigned a match in
     * any {@code (lap, field)} slot. Lower vote = higher scheduling priority (selected first).
     *
     * <p>Ports {@code de.vvwerratal.vvw.tournaments.services.match.MatchDistributor.AvatarVoting}
     * lines 267-293 (simplified: no per-field voting, no unregisterSlot).
     */
    private static final class AvatarVoting {
        private int count = 0;

        void increment() {
            count++;
        }

        int getCount() {
            return count;
        }
    }

    /**
     * Vote counter for a single group. Incremented every time any avatar belonging to this group is
     * assigned a match. Lower vote = higher scheduling priority.
     *
     * <p>Ports {@code de.vvwerratal.vvw.tournaments.services.match.MatchDistributor.GroupVoting}
     * lines 281-293 (simplified: no per-field voting, no unregisterSlot).
     */
    private static final class GroupVoting {
        private int count = 0;

        void increment() {
            count++;
        }

        int getCount() {
            return count;
        }
    }

    /**
     * A match wrapped with its associated voting counters. The combined vote is the sum of the two
     * avatar vote counts and the group vote count.
     *
     * <p>Ports {@code de.vvwerratal.vvw.tournaments.services.match.MatchDistributor.VotedMatch}
     * lines 295-330. Simplified: combined vote computed inline; no {@code registerSlot} / {@code
     * unregisterSlot} indirection (voting is mutated directly via {@link AvatarVoting#increment()}
     * and {@link GroupVoting#increment()}).
     */
    private static final class VotedMatch {
        final Match match;
        final AvatarVoting avatar1Voting;
        final AvatarVoting avatar2Voting;
        final GroupVoting groupVoting;

        VotedMatch(
                Match match,
                AvatarVoting avatar1Voting,
                AvatarVoting avatar2Voting,
                GroupVoting groupVoting) {
            this.match = match;
            this.avatar1Voting = avatar1Voting;
            this.avatar2Voting = avatar2Voting;
            this.groupVoting = groupVoting;
        }

        /** Combined vote = avatar1.count + avatar2.count + group.count. */
        int combinedVote() {
            return avatar1Voting.getCount() + avatar2Voting.getCount() + groupVoting.getCount();
        }
    }
}
