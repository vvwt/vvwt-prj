package de.vvwt.tm.domain.generator;

import de.vvwt.tm.domain.Match;
import de.vvwt.tm.domain.MatchFormat;
import de.vvwt.tm.domain.MatchState;
import de.vvwt.tm.domain.Phase;
import de.vvwt.tm.domain.TeamAvatar;
import de.vvwt.tm.domain.Tournament;
import de.vvwt.tm.domain.repo.TournamentRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Round-robin match generator: every avatar plays every other avatar exactly once (AC2).
 *
 * <p>For N avatars, produces {@code N * (N-1) / 2} matches. Handles odd team counts via
 * the standard bye-round technique: a phantom "bye" entry is added to make the count even,
 * pairings are computed for all N+1 slots, and any pairing involving the phantom is dropped
 * (AC3). The real match count is still {@code N * (N-1) / 2}.
 *
 * <p>The classic <em>circle method</em> (also called <em>polygon method</em>) is used to
 * generate all rounds deterministically. In this method, one team is fixed at the "top"
 * while the others rotate clockwise around a polygon. This guarantees that every pair
 * appears in exactly one round and the pairing list is deterministic for the same input
 * avatar order (AC5). Legacy {@code MatchGenerator4roundrobin} uses the same method
 * (see Brief O-14, AC10 regression gate).
 *
 * <h2>Tenant scope (DEC-5, DEC-17)</h2>
 * <p>{@code tenantId} is propagated from the {@link Phase} on every generated {@link Match}.
 *
 * <h2>set_limit derivation (AC4)</h2>
 * <p>The generator reads the parent {@link Tournament} via {@link TournamentRepository} and
 * derives {@code set_limit = MatchFormat.fromPersistedName(tournament.matchFormat).maxSets}.
 *
 * @see MatchGenerator
 * @see MatchGeneratorRegistry
 * @see <a href="../../../../../../../../.gaai/project/contexts/artefacts/stories/E03S09.story.md">Story E03S09</a>
 */
@Component("roundRobin")
public class RoundRobinMatchGenerator implements MatchGenerator {

    private static final Logger log = LoggerFactory.getLogger(RoundRobinMatchGenerator.class);

    /** Sentinel UUID used internally to mark the phantom bye-round entry. Never persisted. */
    private static final UUID PHANTOM_ID = new UUID(0L, 0L);

    private final TournamentRepository tournamentRepository;

    /**
     * Constructs the generator. Spring injects the tournament repository.
     *
     * @param tournamentRepository repository for looking up tournament match-format (AC4)
     */
    public RoundRobinMatchGenerator(TournamentRepository tournamentRepository) {
        if (tournamentRepository == null) {
            throw new IllegalArgumentException("tournamentRepository must not be null");
        }
        this.tournamentRepository = tournamentRepository;
    }

    @Override
    public String getBeanId() {
        return "roundRobin";
    }

    /**
     * {@inheritDoc}
     *
     * <p>Generates the full round-robin pairing set using the circle method.
     * Delegates to {@link #generatePairs(List)} for the core algorithm.
     *
     * @param phase   phase for which matches are generated (AC2 — tenant, tournament, phase FKs)
     * @param avatars already-persisted avatars to pair (AC2, AC3)
     * @return deterministically-ordered list of new {@link Match} entities (not yet persisted)
     */
    @Override
    public List<Match> generate(Phase phase, List<TeamAvatar> avatars) {
        // AC13 — input validation
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
        // Duplicate-ID check
        Set<UUID> seen = new HashSet<>();
        for (TeamAvatar avatar : avatars) {
            if (!seen.add(avatar.getId())) {
                throw new IllegalArgumentException(
                        "Duplicate avatar ID in input list: " + avatar.getId());
            }
        }

        // AC12 — fewer than 2 avatars → empty list
        if (avatars.size() < 2) {
            log.info("[roundRobin] generate: avatarCount={}, matches=0 (< 2 avatars — no matches possible)",
                    avatars.size());
            return Collections.emptyList();
        }

        // AC4 — look up tournament to derive set_limit
        UUID tournamentId = phase.getTournamentId();
        Optional<Tournament> tournamentOpt = tournamentRepository.findById(tournamentId);
        if (tournamentOpt.isEmpty()) {
            throw new IllegalStateException(
                    "Tournament not found for id=" + tournamentId
                    + " (referenced by phase " + phase.getId() + ")");
        }
        Tournament tournament = tournamentOpt.get();
        int setLimit = MatchFormat.fromPersistedName(tournament.getMatchFormat()).getMaxSets();

        // Core algorithm
        List<UUID[]> pairs = generatePairs(avatars);

        // Build Match entities
        LocalDateTime now = LocalDateTime.now();
        List<Match> matches = new ArrayList<>(pairs.size());
        for (UUID[] pair : pairs) {
            Match match = new Match(
                    UUID.randomUUID(),          // id
                    phase.getTenantId(),        // tenantId (DEC-5, DEC-17)
                    phase.getTournamentId(),    // tournamentId
                    phase.getId(),             // phaseId
                    pair[0],                   // memberAvatar1Id
                    pair[1],                   // memberAvatar2Id
                    MatchState.OPEN.getLegacyCode(),  // state = 0
                    setLimit,                  // set_limit (AC4)
                    null,                      // lapNumber — null until E04 (AC2)
                    null,                      // fieldNumber — null until E04 (AC2)
                    null,                      // refereeTeamId — null
                    null,                      // refereeDescription — null
                    null,                      // refereePreferenceConfig — null
                    now                        // createdAt — set by generator (schema has NOT NULL DEFAULT)
            );
            matches.add(match);
            log.debug("[roundRobin] pairing: {} vs {}", pair[0], pair[1]);
        }

        log.info("[roundRobin] generate: beanId=roundRobin, avatarCount={}, matchesGenerated={}",
                avatars.size(), matches.size());

        return Collections.unmodifiableList(matches);
    }

    /**
     * Core round-robin pairing algorithm (circle method).
     *
     * <p>Produces all {@code N*(N-1)/2} pairs for N avatars. For odd N, a phantom entry
     * (UUID = {@link #PHANTOM_ID}) is added to make N even; any pair involving the phantom
     * is dropped from the result (AC3).
     *
     * <p>The circle method:
     * <ol>
     *   <li>Build a slot array of size N (or N+1 for odd). Slot 0 is "fixed".</li>
     *   <li>For each of the N-1 (or N for odd) rounds, pair slot 0 with the last slot,
     *       then pair slot 1 with slot N-2, slot 2 with slot N-3, etc.</li>
     *   <li>After each round, rotate slots 1..N-1 one position clockwise.</li>
     * </ol>
     *
     * <p>This is the same algorithm as the legacy {@code MatchGenerator4roundrobin}
     * and produces an identical pair set for the same input (AC10 regression gate).
     *
     * @param avatars the (non-null, non-empty, duplicate-free) avatar list; must have size &ge; 2
     * @return list of pairs (each a 2-element UUID array [avatar1Id, avatar2Id]);
     *         never contains the phantom ID; deterministic for the same input
     */
    List<UUID[]> generatePairs(List<TeamAvatar> avatars) {
        int n = avatars.size();
        boolean addPhantom = (n % 2 != 0);
        int slots = addPhantom ? n + 1 : n;
        int rounds = slots - 1;

        // Build slot array: index 0..n-1 are real avatar IDs; index n (if phantom) = PHANTOM_ID
        UUID[] slot = new UUID[slots];
        for (int i = 0; i < n; i++) {
            slot[i] = avatars.get(i).getId();
        }
        if (addPhantom) {
            slot[n] = PHANTOM_ID;
        }

        List<UUID[]> pairs = new ArrayList<>(n * (n - 1) / 2);

        for (int round = 0; round < rounds; round++) {
            // Pair up this round
            for (int i = 0; i < slots / 2; i++) {
                UUID a = slot[i];
                UUID b = slot[slots - 1 - i];
                // Skip any pairing involving the phantom (bye round)
                if (!PHANTOM_ID.equals(a) && !PHANTOM_ID.equals(b)) {
                    pairs.add(new UUID[]{a, b});
                }
            }

            // Rotate slots 1..slots-1 one position clockwise:
            // slot[1] = old slot[slots-1], slot[2..] shift right
            UUID last = slot[slots - 1];
            System.arraycopy(slot, 1, slot, 2, slots - 2);
            slot[1] = last;
        }

        return pairs;
    }
}
