package de.vvwt.tm.tournament.internal;

import com.fasterxml.jackson.databind.ObjectMapper;
import de.vvwt.tm.tournament.MatchGenJobExecutor;
import de.vvwt.tm.tournament.Phase;
import de.vvwt.tm.tournament.PhaseLifecycleService;
import de.vvwt.tm.tournament.PhasePreparationService;
import de.vvwt.tm.tournament.PhaseRepository;
import de.vvwt.tm.tournament.RoundAssignmentService;
import de.vvwt.tm.tournament.Tournament;
import de.vvwt.tm.tournament.TournamentRepository;
import de.vvwt.tm.tournament.draft.DraftConfig;
import de.vvwt.tm.tournament.draft.DraftSection;
import de.vvwt.tm.tournament.draft.GameMode;
import de.vvwt.tm.tournament.events.SlotOptJobScheduledEvent;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Executes the Match-Gen job for a single phase in a dedicated {@code REQUIRES_NEW} transaction.
 *
 * <p>Extracted from {@link MatchGenJobListener} to avoid Spring AOP transaction-commit semantics
 * from leaking {@link org.springframework.transaction.UnexpectedRollbackException} into the
 * {@code @Async} caller: when a {@code @Transactional(REQUIRES_NEW)} method catches its own
 * exception and returns normally, the TX is still rollback-only — Spring will throw {@code
 * UnexpectedRollbackException} during commit. By extracting the transactional work into a separate
 * component:
 *
 * <ol>
 *   <li>The {@code @Async} listener ({@link MatchGenJobListener}) is NOT {@code @Transactional}.
 *   <li>This executor's {@code execute()} is {@code @Transactional(REQUIRES_NEW)} — its TX is
 *       isolated and any exception propagates cleanly to the listener's catch block WITHOUT leaving
 *       a rollback-only marker on the listener method's stack frame.
 *   <li>The listener's catch block calls {@link MatchGenFailureWriter#writeFailedState} in yet
 *       another {@code REQUIRES_NEW} TX to commit the {@code 'failed'} state.
 * </ol>
 *
 * <h2>L2 Round-Assignment wiring (E51S10)</h2>
 *
 * <p>After L1 match-generation, this executor invokes {@link RoundAssignmentService} to assign
 * {@code lapNumber} and {@code fieldNumber} to every generated match. The {@code fieldCount} is
 * resolved from {@link Tournament#getFieldCount()} (operator-set). If {@code fieldCount} is {@code
 * 0} or negative (e.g., DB NULL maps to {@code 0} on the primitive {@code int} field), the fallback
 * value from {@code tm.slotopt.fallback.field-count} (default 3) is used — the same property
 * previously read via {@code PhaseToRawPhaseDefMapper.getFieldCount()} (DEC-55 D-13 fallback rule).
 *
 * <h2>B-b1 cycle-break (E51S16)</h2>
 *
 * <p>The previous injection of {@code PhaseToRawPhaseDefMapper} created a {@code tournament →
 * slotopt} compile-time edge, causing the Modulith cycle {@code slotopt → tournament → slotopt}.
 * E51S16 removes this edge by injecting the config property directly via
 * {@code @Value("${tm.slotopt.fallback.field-count:3}")}. Spring's PropertyResolver resolves the
 * same configured value as before; the module boundary is no longer violated.
 *
 * @see DefaultMatchGenJobListener
 * @see DefaultMatchGenFailureWriter
 * @see RoundAssignmentService
 * @see <a href="DEC-21">DEC-21 — Spring Modulith; tournament allowedDependencies = tenant only</a>
 * @see <a href="DEC-37">DEC-37 Clause B — per-tournament row-lock as first read</a>
 * @see <a href="DEC-55">DEC-55 D-3 — Background-Job-Pipeline (events-only)</a>
 * @see <a href="DEC-58">DEC-58 — Universal interface mandate for self-created Spring components</a>
 * @see <a href="E51S03">E51S03 — Background-Job-Pipeline foundation</a>
 * @see <a href="E51S10">E51S10 — L2 Round-Assignment Service + fieldCount wiring</a>
 * @see <a href="E51S16">E51S16 — B-b1 Modulith-cycle elimination</a>
 */
@Component
public class DefaultMatchGenJobExecutor implements MatchGenJobExecutor {

    private static final Logger LOG = LoggerFactory.getLogger(DefaultMatchGenJobExecutor.class);

    private final PhasePreparationService phasePreparationService;
    private final TournamentRepository tournamentRepository;
    private final PhaseRepository phaseRepository;
    private final ApplicationEventPublisher eventPublisher;
    private final RoundAssignmentService roundAssignmentService;
    private final ObjectMapper objectMapper;

    /**
     * Fallback field-count when {@link Tournament#getFieldCount()} is 0 or negative (D-13 fallback
     * rule). Configured via {@code tm.slotopt.fallback.field-count} (default 3). This value must be
     * EXACTLY {@code ${tm.slotopt.fallback.field-count:3}} — character-identical to the literal in
     * {@code PhaseToRawPhaseDefMapper} and {@code FallbackSlotOptimizationClient} — to ensure
     * Spring PropertyResolver provides the same resolved value at all declaration sites
     * (AC-IMPL-FALLBACK- RESOLUTION-PRESERVED, DEC-55 D-13).
     */
    private final int fallbackFieldCount;

    /**
     * Advances the phase lifecycle to {@code PREPARED} after successful match-gen (DEC-55 D-4).
     *
     * <p>Wired via E51S14: closes the PENDING→PREPARED gap by invoking {@code transition(phaseId,
     * PREPARED, "match-gen-done")} on the success path within the same {@code REQUIRES_NEW}
     * transaction as match-generation and round-assignment.
     */
    private final PhaseLifecycleService phaseLifecycleService;

    DefaultMatchGenJobExecutor(
            PhasePreparationService phasePreparationService,
            TournamentRepository tournamentRepository,
            PhaseRepository phaseRepository,
            ApplicationEventPublisher eventPublisher,
            RoundAssignmentService roundAssignmentService,
            @Value("${tm.slotopt.fallback.field-count:3}") int fallbackFieldCount,
            @Qualifier("tmPhaseLifecycleService") PhaseLifecycleService phaseLifecycleService,
            ObjectMapper objectMapper) {
        this.phasePreparationService = phasePreparationService;
        this.tournamentRepository = tournamentRepository;
        this.phaseRepository = phaseRepository;
        this.eventPublisher = eventPublisher;
        this.roundAssignmentService = roundAssignmentService;
        this.fallbackFieldCount = fallbackFieldCount;
        this.phaseLifecycleService = phaseLifecycleService;
        this.objectMapper = objectMapper;
    }

    /**
     * Executes the match-gen + L2 round-assignment job for the given phase in a fresh transaction.
     *
     * <p>Steps (per DEC-55 D-3 + E51S10 L2 wiring):
     *
     * <ol>
     *   <li>Acquire per-tournament row-lock (DEC-37 Clause B).
     *   <li>Load phase; idempotency check ({@code last_job_state='idle'} → skip).
     *   <li>Invoke {@link PhasePreparationService#generateMatches(UUID, String)} (L1).
     *   <li>Resolve {@code fieldCount} from {@link Tournament#getFieldCount()} with D-13 fallback.
     *   <li>Invoke {@link RoundAssignmentService#assignRoundsAndFields(UUID, int)} (L2).
     *   <li>Set {@code phase.last_job_state='idle'}.
     *   <li>Advance phase lifecycle: {@code PENDING → PREPARED} via {@code "match-gen-done"}
     *       (DEC-55 D-4, E51S14).
     *   <li>If {@code tournament.optimize=true}: publish {@link SlotOptJobScheduledEvent}.
     * </ol>
     *
     * <p>On success the transaction commits. On exception the transaction rolls back and the
     * exception propagates to the caller ({@link MatchGenJobListener}) for failure handling.
     *
     * @param tournamentId the tournament UUID (for row-lock, fieldCount, and optimize flag)
     * @param phaseId the phase UUID (for match-generation and round-assignment)
     * @throws RuntimeException if match-generation or round-assignment fails; propagates to
     *     listener for failure write
     */
    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void execute(UUID tournamentId, UUID phaseId) {
        // Step 1: DEC-37 Clause B — acquire per-tournament row-lock as the FIRST read
        Tournament tournament = tournamentRepository.findByIdForUpdate(tournamentId);

        // Step 2: Load phase
        Phase phase =
                phaseRepository
                        .findById(phaseId)
                        .orElseThrow(
                                () -> new IllegalArgumentException("Phase not found: " + phaseId));

        // Step 3: Idempotency check — skip if already 'idle'
        if ("idle".equals(phase.getLastJobState())) {
            LOG.info(
                    "MatchGenJobExecutor: phase={} last_job_state='idle' — skipping (idempotent)",
                    phaseId);
            return;
        }

        // Step 4: Resolve per-phase generator key (DEC-59 Clause E: siegerehrung uses
        // "siegerehrung" key; non-siegerehrung phases use tournament.matchGeneratorId).
        // Uses draftJson section at (phase.sequenceNumber - 1) to determine gameMode.
        String generatorKey = resolveGeneratorKey(tournament, phase);
        phasePreparationService.generateMatches(phaseId, generatorKey);

        // Step 5: Resolve fieldCount with D-13 fallback (tournament.fieldCount → @Value default)
        // tournament.fieldCount is an int primitive; DB NULL maps to 0.
        int resolvedFieldCount = tournament.getFieldCount();
        if (resolvedFieldCount < 1) {
            // D-13 fallback: use tm.slotopt.fallback.field-count config (injected via @Value)
            resolvedFieldCount = fallbackFieldCount;
            LOG.info(
                    "MatchGenJobExecutor: tournament.fieldCount={} → applying D-13 fallback,"
                            + " using @Value fallbackFieldCount={}",
                    tournament.getFieldCount(),
                    resolvedFieldCount);
        }

        // Step 6: Invoke L2 round-assignment (within same REQUIRES_NEW TX — atomic with L1)
        roundAssignmentService.assignRoundsAndFields(phaseId, resolvedFieldCount);

        // Step 7: Set last_job_state = 'idle' on success (after L1+L2 complete)
        phase.setLastJobState("idle");
        phaseRepository.save(phase);

        // Step 7b: Advance phase lifecycle PENDING → PREPARED (DEC-55 D-4, E51S14)
        phaseLifecycleService.transition(phaseId, Phase.PhaseStatus.PREPARED, "match-gen-done");

        LOG.info(
                "MatchGenJobExecutor: SUCCESS tournamentId={}, phaseId={},"
                        + " generatorKey={}, fieldCount={}",
                tournamentId,
                phaseId,
                generatorKey,
                resolvedFieldCount);

        // Step 8: Publish SlotOptJobScheduledEvent if tournament.optimize=true (DEC-55 D-3)
        // DEC-59 Clause E: siegerehrung phases are excluded from slot-optimization — they have no
        // matches to optimize. The siegerehrung generator returns an empty match list, so
        // SlotOpt would have nothing to do. Guard: !isSiegerehrungPhase(tournament, phase).
        if (tournament.isOptimize() && !isSiegerehrungPhase(tournament, phase)) {
            SlotOptJobScheduledEvent slotOptEvent =
                    new SlotOptJobScheduledEvent(tournamentId, phaseId);
            eventPublisher.publishEvent(slotOptEvent);
            LOG.info(
                    "MatchGenJobExecutor: published SlotOptJobScheduledEvent"
                            + " tournamentId={}, phaseId={}",
                    tournamentId,
                    phaseId);
        }
    }

    /**
     * Resolves the match generator key for the given phase.
     *
     * <p>For siegerehrung phases (determined by parsing {@code tournament.draftJson}), returns
     * {@code "siegerehrung"} to dispatch to {@link SiegerehrungMatchGenerator} (which returns an
     * empty match list). For all other phases, returns {@code tournament.getMatchGeneratorId()}
     * (the tournament-level generator key, e.g., {@code "roundRobin"}).
     *
     * <p>DEC-59 Clause E: siegerehrung phases go through the full match-gen pipeline with a vacuous
     * L1 (empty match list) and vacuous L2 (no matches to assign), then transition PENDING→PREPARED
     * via the standard "match-gen-done" verb (DEC-55 D-4).
     *
     * @param tournament the tournament (provides draftJson and fallback matchGeneratorId)
     * @param phase the phase to resolve the generator key for
     * @return the generator key; never {@code null}
     */
    private String resolveGeneratorKey(Tournament tournament, Phase phase) {
        if (isSiegerehrungPhase(tournament, phase)) {
            return GameMode.SIEGEREHRUNG.getWireFormat();
        }
        return tournament.getMatchGeneratorId();
    }

    /**
     * Returns {@code true} if the given phase corresponds to a siegerehrung section in the
     * tournament's {@code draftJson}.
     *
     * <p>Parses {@code tournament.draftJson} to find the section at index {@code
     * phase.sequenceNumber - 1} (0-based) and checks if its {@code gameMode} equals {@code
     * "siegerehrung"}. Returns {@code false} on any parsing error (fail-safe: non-siegerehrung
     * behavior preserved).
     *
     * @param tournament the tournament (provides draftJson)
     * @param phase the phase to check
     * @return {@code true} if the phase is a siegerehrung phase; {@code false} otherwise
     */
    private boolean isSiegerehrungPhase(Tournament tournament, Phase phase) {
        try {
            String draftJson = tournament.getDraftJson();
            if (draftJson == null || draftJson.isBlank()) {
                return false;
            }
            DraftConfig draftConfig = objectMapper.readValue(draftJson, DraftConfig.class);
            List<DraftSection> sections = draftConfig.getSections();
            int sectionIndex = phase.getSequenceNumber() - 1;
            if (sectionIndex < 0 || sectionIndex >= sections.size()) {
                return false;
            }
            return sections.get(sectionIndex).getGameMode() == GameMode.SIEGEREHRUNG;
        } catch (Exception e) {
            LOG.warn(
                    "MatchGenJobExecutor: failed to parse draftJson for tournament={}"
                            + " phaseId={} — defaulting to non-siegerehrung (fail-safe)",
                    tournament.getId(),
                    phase.getId(),
                    e);
            return false;
        }
    }
}
