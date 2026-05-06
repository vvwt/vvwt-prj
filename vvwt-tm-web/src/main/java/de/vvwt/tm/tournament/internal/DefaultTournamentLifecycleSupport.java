package de.vvwt.tm.tournament.internal;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import de.vvwt.tm.tournament.Phase;
import de.vvwt.tm.tournament.PhaseRepository;
import de.vvwt.tm.tournament.Tournament;
import de.vvwt.tm.tournament.TournamentLifecycleSupport;
import de.vvwt.tm.tournament.TournamentRepository;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * Default implementation of {@link TournamentLifecycleSupport} (DEC-35, E48S02).
 *
 * <p>Provides read-only helper operations for tournament lifecycle logic consumed by E48S06 and
 * E48S07. All methods are pure reads — no DEC-37 Clause B lock required.
 *
 * <h2>isLastPhase</h2>
 *
 * <p>Loads all phases for the tournament (via {@link PhaseRepository#findByTournamentId(UUID)}),
 * sorts by {@code sequenceNumber} descending, and compares the head element's id with the requested
 * {@code phaseId}. Pure read, no write lock.
 *
 * <h2>readGameMode (package-visible helper)</h2>
 *
 * <p>Parses the tournament's {@code draft_json} to extract the {@code gameMode} from the first
 * section. Throws {@link IllegalStateException} when {@code draft_json} is {@code null}, blank, or
 * not valid JSON (AC-IMPL-DRAFT-JSON-DEFENSIVE, C-14). Never silently fails.
 *
 * @see TournamentLifecycleSupport
 * @see <a href="DEC-35">DEC-35 — impl in tournament.internal; interface in public package</a>
 * @see <a href="DEC-37">DEC-37 Clause B — lock NOT required for pure reads</a>
 * @see <a href="E48S02">E48S02 — AC-IMPL-IS-LAST-PHASE-HELPER, AC-IMPL-DRAFT-JSON-DEFENSIVE</a>
 */
@Service("defaultTournamentLifecycleSupport")
public class DefaultTournamentLifecycleSupport implements TournamentLifecycleSupport {

    private static final Logger LOG =
            LoggerFactory.getLogger(DefaultTournamentLifecycleSupport.class);

    private final PhaseRepository phaseRepository;
    private final TournamentRepository tournamentRepository;
    private final ObjectMapper objectMapper;

    /**
     * Constructs the service with required collaborators.
     *
     * @param phaseRepository repository for reading phase data
     * @param tournamentRepository repository for reading tournament data (incl. draft_json)
     */
    @Autowired
    public DefaultTournamentLifecycleSupport(
            PhaseRepository phaseRepository, TournamentRepository tournamentRepository) {
        this(phaseRepository, tournamentRepository, new ObjectMapper());
    }

    /**
     * Package-visible constructor for tests that supply a custom {@link ObjectMapper}.
     *
     * @param phaseRepository repository for reading phase data
     * @param tournamentRepository repository for reading tournament data
     * @param objectMapper Jackson mapper for parsing draft_json
     */
    DefaultTournamentLifecycleSupport(
            PhaseRepository phaseRepository,
            TournamentRepository tournamentRepository,
            ObjectMapper objectMapper) {
        this.phaseRepository = phaseRepository;
        this.tournamentRepository = tournamentRepository;
        this.objectMapper = objectMapper;
    }

    /**
     * {@inheritDoc}
     *
     * <p>Loads all phases for the tournament referenced by the given phase, sorts by {@code
     * sequenceNumber} DESC, and returns {@code true} if the given phase has the highest sequence
     * number.
     */
    @Override
    public boolean isLastPhase(UUID phaseId) {
        if (phaseId == null) {
            throw new IllegalArgumentException("phaseId must not be null");
        }

        Phase phase =
                phaseRepository
                        .findById(phaseId)
                        .orElseThrow(
                                () -> new IllegalArgumentException("Phase not found: " + phaseId));

        List<Phase> phases = phaseRepository.findByTournamentId(phase.getTournamentId());

        if (phases.isEmpty()) {
            // Defensive: phase was just loaded successfully; this should not occur in practice.
            LOG.warn(
                    "isLastPhase: no phases found for tournamentId={} — phaseId={} assumed last",
                    phase.getTournamentId(),
                    phaseId);
            return true;
        }

        Phase lastPhase =
                phases.stream()
                        .max(Comparator.comparingInt(Phase::getSequenceNumber))
                        .orElseThrow();

        boolean isLast = lastPhase.getId().equals(phaseId);

        LOG.debug(
                "isLastPhase: phaseId={}, lastPhaseId={}, sequenceNumber={}, result={}",
                phaseId,
                lastPhase.getId(),
                lastPhase.getSequenceNumber(),
                isLast);

        return isLast;
    }

    /**
     * Reads the {@code gameMode} from the first section of the tournament's {@code draft_json}.
     *
     * <p>Throws {@link IllegalStateException} with an operator-actionable message when {@code
     * draft_json} is {@code null}, blank, or syntactically invalid JSON
     * (AC-IMPL-DRAFT-JSON-DEFENSIVE, C-14 — never silent-failing). Throws {@link
     * IllegalArgumentException} if the tournament does not exist.
     *
     * <p>This method is {@code package-visible} (not on the public interface) because it is a
     * shared helper for E48S06 and E48S07 internal logic; it is NOT part of the public {@link
     * TournamentLifecycleSupport} contract.
     *
     * @param tournamentId the tournament UUID
     * @return the {@code gameMode} string from the first {@code sections[]} entry in {@code
     *     draft_json}
     * @throws IllegalArgumentException if no tournament with the given id exists
     * @throws IllegalStateException if {@code draft_json} is {@code null}, blank, or not valid JSON
     *     (message: {@code "Tournament <id> has no valid draft_json — apply a draft first"})
     */
    String readGameMode(UUID tournamentId) {
        Tournament tournament =
                tournamentRepository
                        .findById(tournamentId)
                        .orElseThrow(
                                () ->
                                        new IllegalArgumentException(
                                                "Tournament not found: " + tournamentId));

        String draftJson = tournament.getDraftJson();
        if (draftJson == null || draftJson.isBlank()) {
            throw new IllegalStateException(
                    "Tournament "
                            + tournamentId
                            + " has no valid draft_json — apply a draft first");
        }

        try {
            JsonNode root = objectMapper.readTree(draftJson);
            JsonNode sections = root.path("sections");
            if (sections.isMissingNode() || !sections.isArray() || sections.isEmpty()) {
                throw new IllegalStateException(
                        "Tournament "
                                + tournamentId
                                + " has no valid draft_json — apply a draft first");
            }
            JsonNode firstSection = sections.get(0);
            JsonNode gameMode = firstSection.path("gameMode");
            if (gameMode.isMissingNode() || gameMode.isNull()) {
                throw new IllegalStateException(
                        "Tournament "
                                + tournamentId
                                + " has no valid draft_json — apply a draft first");
            }
            return gameMode.asText();
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException(
                    "Tournament " + tournamentId + " has no valid draft_json — apply a draft first",
                    e);
        }
    }
}
