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
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Round-robin match generator: every avatar plays every other avatar exactly once (E21S08).
 *
 * <p>Reconstruction-in-place counterpart of {@code
 * de.vvwt.tm.domain.generator.RoundRobinMatchGenerator} (inventory row 258). Lives at {@code
 * de.vvwt.tm.tournament.internal} — the Modulith internal package per DEC-21 § Module layout. Uses
 * new {@code de.vvwt.tm.tournament.*} types.
 *
 * <p>For N avatars, produces {@code N * (N-1) / 2} matches. Handles odd team counts via the phantom
 * bye-round technique (adds a sentinel, drops phantom-involving pairings).
 *
 * <p>The classic <em>circle method</em> generates all rounds deterministically. One team is fixed
 * at "top" while the others rotate clockwise — every pair appears exactly once.
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

    @Override
    public String getBeanId() {
        return "roundRobin";
    }

    /**
     * {@inheritDoc}
     *
     * <p>Generates the full round-robin pairing set using the circle method. Returns an immutable
     * list.
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

        if (avatars.size() < 2) {
            LOG.info(
                    "[roundRobin] generate: avatarCount={}, matches=0 (< 2 — no matches"
                            + " possible)",
                    avatars.size());
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

        List<UUID[]> pairs = generatePairs(avatars);

        LocalDateTime now = LocalDateTime.now();
        List<Match> matches = new ArrayList<>(pairs.size());
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
                            null,
                            null,
                            null,
                            null,
                            null,
                            now);
            matches.add(match);
            LOG.debug("[roundRobin] pairing: {} vs {}", pair[0], pair[1]);
        }

        LOG.info(
                "[roundRobin] generate: avatarCount={}, matchesGenerated={}",
                avatars.size(),
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
