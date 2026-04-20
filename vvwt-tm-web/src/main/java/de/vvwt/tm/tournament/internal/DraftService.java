package de.vvwt.tm.tournament.internal;

import de.vvwt.tm.tournament.Phase;
import de.vvwt.tm.tournament.PhaseBreak;
import de.vvwt.tm.tournament.PhaseBreakRepository;
import de.vvwt.tm.tournament.PhaseRepository;
import de.vvwt.tm.tournament.internal.draft.DraftBreak;
import de.vvwt.tm.tournament.internal.draft.DraftConfig;
import de.vvwt.tm.tournament.internal.draft.DraftPreviewResult;
import de.vvwt.tm.tournament.internal.draft.DraftPreviewSection;
import de.vvwt.tm.tournament.internal.draft.DraftSection;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

/**
 * Domain service for draft configuration preview and apply operations.
 *
 * <h2>Scoped API</h2>
 *
 * <ul>
 *   <li>{@link #preview(DraftConfig, int)} — pure computation, no DB side effect
 *   <li>{@link #apply(UUID, DraftConfig)} — creates Phase entities via the Phase-aggregate
 *       collaborators from E21S03
 * </ul>
 *
 * <h2>Idempotency (AC-DRAFT-APPLY-IDEMPOTENCY)</h2>
 *
 * <p>The service <strong>fails-fast</strong> on re-apply: if phases already exist for the
 * tournament, {@link DraftAlreadyAppliedException} is thrown (→ 409 Conflict). This matches the
 * legacy {@code de.vvwt.tm.domain.DraftService.applyDraft()} behaviour confirmed at lines 297–305.
 *
 * <h2>Reconstruction-in-place (DEC-21/DEC-22)</h2>
 *
 * <p>Legacy {@code de.vvwt.tm.domain.DraftService} remains active until E21S13 atomic cutover
 * (DEC-32). This service uses the qualifier {@code "tmDraftService"} to avoid bean-name conflict.
 *
 * <p>Inventory: E21S01 line 171.
 *
 * @see DraftConfig
 * @see DraftPreviewResult
 * @see Phase
 * @see PhaseRepository
 * @see <a href="DEC-21">DEC-21 — Spring Modulith package layout</a>
 * @see <a href="DEC-22">DEC-22 — TDD reconstruction-in-place</a>
 * @see <a href="E21S07">E21S07 — Draft phase-planning reconstruction</a>
 * @see <a href="E21S03">E21S03 — Phase aggregate (collaborator)</a>
 */
@Service("tmDraftService")
public class DraftService {

    private final PhaseRepository phaseRepository;
    private final PhaseBreakRepository phaseBreakRepository;

    /**
     * Constructs the service with Phase-aggregate collaborators from E21S03.
     *
     * @param phaseRepository phase persistence (tenant-scoped, E21S03)
     * @param phaseBreakRepository phase break persistence (tenant-scoped, E21S03)
     */
    public DraftService(
            @Qualifier("tmPhaseRepository") PhaseRepository phaseRepository,
            @Qualifier("tmPhaseBreakRepository") PhaseBreakRepository phaseBreakRepository) {
        this.phaseRepository = phaseRepository;
        this.phaseBreakRepository = phaseBreakRepository;
    }

    // -------------------------------------------------------------------------
    // preview — pure computation
    // -------------------------------------------------------------------------

    /**
     * Calculates a preview of what the draft will produce without creating any entities.
     *
     * <p>For each section, computes: phase number, group count, teams per group, matches per group
     * (round-robin), total laps, total matches, and estimated duration.
     *
     * <p>Timeline entries are currently empty ({@code List.of()}) — the timeline domain classes
     * ({@code TimelineCalculationService}, {@code TimelineEntry}) arrive in E21S11. The result
     * shape is established here; E21S11 will populate the timeline list.
     *
     * @param config the draft configuration to preview; must not be {@code null}
     * @param participatingTeamCount number of participating teams
     * @return preview result; never {@code null}
     * @see <a href="E21S11">E21S11 — Timeline domain classes (future timeline population)</a>
     */
    public DraftPreviewResult preview(DraftConfig config, int participatingTeamCount) {
        List<DraftPreviewSection> previews = new ArrayList<>();
        for (DraftSection section : config.getSections()) {
            previews.add(computePreview(section, participatingTeamCount));
        }
        // Timeline entries deferred to E21S11 (TimelineCalculationService)
        return new DraftPreviewResult(previews, List.of());
    }

    // -------------------------------------------------------------------------
    // apply — phase creation
    // -------------------------------------------------------------------------

    /**
     * Applies the draft configuration to create Phase entities.
     *
     * <p>Fails-fast if phases already exist for the tournament (AC-DRAFT-APPLY-IDEMPOTENCY).
     * Creates one Phase per section. Persists PhaseBreak entities for intra-phase breaks.
     *
     * @param tournamentId the tournament UUID
     * @param config the draft configuration to apply; must not be {@code null}
     * @return ordered list of created Phase IDs (one per section); never empty
     * @throws DraftAlreadyAppliedException if phases already exist (AC-DRAFT-APPLY-IDEMPOTENCY)
     */
    public List<UUID> apply(UUID tournamentId, DraftConfig config) {
        // AC-DRAFT-APPLY-IDEMPOTENCY: fails-fast if phases already exist
        List<Phase> existingPhases = phaseRepository.findByTournamentId(tournamentId);
        if (!existingPhases.isEmpty()) {
            throw new DraftAlreadyAppliedException(tournamentId, existingPhases.size());
        }

        List<UUID> createdPhaseIds = new ArrayList<>();
        for (DraftSection section : config.getSections()) {
            Phase phase =
                    new Phase(
                            UUID.randomUUID(),
                            null, // tenantId — set by repository (tenant-scoped)
                            tournamentId,
                            section.getSectionNumber(),
                            "Phase " + section.getSectionNumber(),
                            Phase.PhaseStatus.PENDING.name(),
                            0,
                            LocalDateTime.now());
            Phase savedPhase = phaseRepository.save(phase);
            createdPhaseIds.add(savedPhase.getId());

            persistPhaseBreaks(savedPhase.getId(), section.getBreaks());
        }

        return createdPhaseIds;
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    /**
     * Computes the preview for one section given the participating team count.
     *
     * @param section the section configuration
     * @param participatingTeamCount total participating teams in the tournament
     * @return the computed preview section
     */
    private DraftPreviewSection computePreview(DraftSection section, int participatingTeamCount) {
        int groupCount = section.getGroupCount();
        int teamsPerGroup = participatingTeamCount / groupCount;
        int matchesPerGroup = teamsPerGroup > 1 ? teamsPerGroup * (teamsPerGroup - 1) / 2 : 0;
        int totalLaps = teamsPerGroup > 1 ? teamsPerGroup - 1 : 0;
        int totalMatches = groupCount * matchesPerGroup;

        int interLapBreaks = Math.max(0, totalLaps - 1) * section.getLapBreakTimeMinutes();
        int lapTime = totalLaps * section.getLapTimeMinutes();
        int intraPhaseBreakTime =
                section.getBreaks().stream().mapToInt(DraftBreak::getDurationMinutes).sum();
        int estimatedTimeMinutes =
                lapTime
                        + interLapBreaks
                        + intraPhaseBreakTime
                        + section.getSectionBreakTimeMinutes();

        return new DraftPreviewSection(
                section.getSectionNumber(),
                groupCount,
                teamsPerGroup,
                matchesPerGroup,
                totalLaps,
                totalMatches,
                estimatedTimeMinutes);
    }

    /**
     * Persists each {@link DraftBreak} from a section as a {@link PhaseBreak} entity.
     *
     * @param phaseId the UUID of the newly created Phase
     * @param breaks the list of breaks from the section; may be empty
     */
    private void persistPhaseBreaks(UUID phaseId, List<DraftBreak> breaks) {
        for (DraftBreak draftBreak : breaks) {
            PhaseBreak phaseBreak =
                    new PhaseBreak(
                            UUID.randomUUID(),
                            null, // tenantId — set by TenantScopedRepository.save()
                            phaseId,
                            draftBreak.getAfterLapNumber(),
                            draftBreak.getDurationMinutes(),
                            draftBreak.getLabel());
            phaseBreakRepository.save(phaseBreak);
        }
    }
}
