package de.vvwt.tm.domain;

import de.vvwt.tm.domain.repo.MatchRepository;
import de.vvwt.tm.domain.repo.PhaseBreakRepository;
import de.vvwt.tm.domain.repo.PhaseRepository;
import de.vvwt.tm.infrastructure.web.ConflictException;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.UUID;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Domain service for managing intra-phase break configuration (E08S01).
 *
 * <p>Handles creation and retrieval of {@link PhaseBreak} entries with full validation:
 *
 * <ul>
 *   <li>AC5 — lap range validation: {@code afterLapNumber} must be ≥ 1 and < totalLaps in phase
 *   <li>AC6 — duplicate detection: at most one break per lap boundary per phase
 *   <li>AC7 — tenant scope: delegated to {@link PhaseBreakRepository}
 *   <li>AC8 — i18n: validation error messages sourced from {@code messages.properties}
 * </ul>
 *
 * <p>Total laps for a phase are derived from the match schedule: the highest {@code lapNumber}
 * across all {@link Match} rows for the phase. This follows from the story definition: "total laps
 * are derived from the phase's match schedule (the highest {@code lap_number} across all matches in
 * the phase)."
 *
 * @see PhaseBreak
 * @see PhaseBreakRepository
 * @see <a
 *     href="../../../../../../../../.gaai/project/contexts/artefacts/stories/E08S01.story.md">Story
 *     E08S01</a>
 */
@Service
public class PhaseBreakService {

    private final PhaseRepository phaseRepository;
    private final MatchRepository matchRepository;
    private final PhaseBreakRepository phaseBreakRepository;
    private final MessageSource messageSource;

    public PhaseBreakService(
            PhaseRepository phaseRepository,
            MatchRepository matchRepository,
            PhaseBreakRepository phaseBreakRepository,
            MessageSource messageSource) {
        this.phaseRepository = phaseRepository;
        this.matchRepository = matchRepository;
        this.phaseBreakRepository = phaseBreakRepository;
        this.messageSource = messageSource;
    }

    /**
     * Creates a new intra-phase break for the given phase.
     *
     * <p>Validation sequence:
     *
     * <ol>
     *   <li>Load the phase (throws {@link NoSuchElementException} if absent or wrong tenant — AC7)
     *   <li>Determine total laps from the match schedule (max {@code lapNumber} across matches)
     *   <li>Validate {@code afterLapNumber} ≥ 1 and < totalLaps (AC5)
     *   <li>Detect duplicate at the same lap boundary (AC6)
     *   <li>Persist and return the new {@link PhaseBreak}
     * </ol>
     *
     * @param phaseId the phase to add the break to (must exist and belong to active tenant)
     * @param afterLapNumber the lap after which the break occurs (1-based; must be < totalLaps)
     * @param durationMinutes the break duration in minutes (must be > 0)
     * @param label optional display label (e.g., "Mittagspause"); may be null
     * @return the persisted {@link PhaseBreak}
     * @throws NoSuchElementException if the phase does not exist or is out of tenant scope
     * @throws IllegalArgumentException if {@code afterLapNumber} is out of range (AC5)
     * @throws ConflictException if a break already exists at {@code afterLapNumber} in the phase
     *     (AC6)
     */
    @Transactional
    public PhaseBreak createPhaseBreak(
            UUID phaseId, int afterLapNumber, int durationMinutes, String label) {
        // Step 1: verify phase exists and is in tenant scope (AC7 — TenantScopedRepository guards)
        phaseRepository
                .findById(phaseId)
                .orElseThrow(() -> new NoSuchElementException("Phase not found: " + phaseId));

        // Step 2: determine total laps for the phase from the match schedule (AC5)
        List<Match> matches = matchRepository.findByPhaseId(phaseId);
        int totalLaps =
                matches.stream()
                        .filter(m -> m.getLapNumber() != null)
                        .mapToInt(Match::getLapNumber)
                        .max()
                        .orElse(0); // 0 means no matches scheduled yet — any afterLapNumber fails
        // validation

        // Step 3: validate lap range (AC5)
        if (afterLapNumber < 1 || afterLapNumber >= totalLaps) {
            String message =
                    messageSource.getMessage(
                            "phase_break.invalid_lap_range", null, LocaleContextHolder.getLocale());
            throw new IllegalArgumentException(message);
        }

        // Step 4: duplicate detection (AC6)
        phaseBreakRepository
                .findByPhaseIdAndAfterLapNumber(phaseId, afterLapNumber)
                .ifPresent(
                        existing -> {
                            String message =
                                    messageSource.getMessage(
                                            "phase_break.duplicate_lap_position",
                                            null,
                                            LocaleContextHolder.getLocale());
                            throw new ConflictException(message);
                        });

        // Step 5: create and persist (AC3, AC7 — tenant set by TenantScopedRepository.save)
        PhaseBreak phaseBreak =
                new PhaseBreak(
                        UUID.randomUUID(),
                        null, // tenantId: TenantScopedRepository.save() sets this from
                        // TenantContext
                        phaseId,
                        afterLapNumber,
                        durationMinutes,
                        label);

        return phaseBreakRepository.save(phaseBreak);
    }

    /**
     * Returns all phase breaks for the given phase, scoped to the active tenant.
     *
     * <p>Used by the timeline calculation service (E08S03) and the print layout (E08S08).
     *
     * @param phaseId the phase to query
     * @return list of phase breaks for the phase, ordered as stored; never {@code null}
     * @throws IllegalStateException if no tenant context is active (AC7)
     */
    @Transactional(readOnly = true)
    public List<PhaseBreak> findByPhaseId(UUID phaseId) {
        return phaseBreakRepository.findByPhaseId(phaseId);
    }
}
