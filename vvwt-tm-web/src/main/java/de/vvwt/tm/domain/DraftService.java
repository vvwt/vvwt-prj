package de.vvwt.tm.domain;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import de.vvwt.tm.domain.draft.DraftBreak;
import de.vvwt.tm.domain.draft.DraftConfig;
import de.vvwt.tm.domain.draft.DraftPreviewResult;
import de.vvwt.tm.domain.draft.DraftPreviewSection;
import de.vvwt.tm.domain.draft.DraftSection;
import de.vvwt.tm.domain.repo.PhaseBreakRepository;
import de.vvwt.tm.domain.repo.PhaseRepository;
import de.vvwt.tm.domain.repo.TeamAvatarRepository;
import de.vvwt.tm.domain.repo.TeamRepository;
import de.vvwt.tm.domain.repo.TournamentRepository;
import de.vvwt.tm.domain.timeline.PhaseBreakConfig;
import de.vvwt.tm.domain.timeline.PhaseConfig;
import de.vvwt.tm.domain.timeline.TimelineCalculationService;
import de.vvwt.tm.domain.timeline.TimelineEntry;
import de.vvwt.tm.infrastructure.web.ConflictException;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.UUID;

/**
 * Domain service for draft configuration, preview, and apply operations (E05S06).
 *
 * <h2>Draft lifecycle</h2>
 * <ol>
 *   <li>AC2: {@link #saveDraft} — serialize sections to JSON, store on tournament</li>
 *   <li>AC3: {@link #getDraft} — read JSON, return {@link DraftConfig} (empty if no draft)</li>
 *   <li>AC4: {@link #previewDraft} — calculate phase/group/match counts without persisting</li>
 *   <li>AC5–AC7: {@link #applyDraft} — create Phase entities, distribute Phase 1 TeamAvatars,
 *       transition tournament to PLANNED</li>
 * </ol>
 *
 * <h2>Business rules enforced</h2>
 * <ul>
 *   <li>AC2: only DRAFT-status tournaments may save/modify a draft</li>
 *   <li>AC5: apply requires DRAFT status, ≥ 1 section, ≥ 2 participating teams</li>
 *   <li>AC7: apply transitions tournament DRAFT → PLANNED (DEC-5 safe: only ACTIVE is constrained)</li>
 *   <li>AC11: apply validates setQuantity against tournament matchFormat</li>
 *   <li>AC12: apply is rejected if phases already exist (tournament already applied)</li>
 *   <li>AC14: all operations are tenant-scoped via the TenantContext (via repositories)</li>
 * </ul>
 *
 * @see <a href="../../../../../../.gaai/project/contexts/artefacts/stories/E05S06.story.md">Story E05S06</a>
 */
@Service
public class DraftService {

    /** Tournament lifecycle status after draft is applied — intermediate before ACTIVE (AC7). */
    static final String STATUS_PLANNED = "PLANNED";
    static final String STATUS_DRAFT = "DRAFT";

    private final TournamentRepository tournamentRepository;
    private final PhaseRepository phaseRepository;
    private final PhaseBreakRepository phaseBreakRepository;
    private final TeamRepository teamRepository;
    private final TeamAvatarRepository teamAvatarRepository;
    private final TimelineCalculationService timelineCalculationService;
    private final ObjectMapper objectMapper;

    /**
     * Constructs the service with all required collaborators.
     *
     * @param tournamentRepository       tournament persistence (tenant-scoped)
     * @param phaseRepository            phase persistence (tenant-scoped) — for apply and re-apply check
     * @param phaseBreakRepository       phase break persistence (tenant-scoped) — for apply (AC7 — E08S05)
     * @param teamRepository             team persistence (tenant-scoped) — for preview/apply team count
     * @param teamAvatarRepository       team avatar persistence (tenant-scoped) — for Phase 1 distribution
     * @param timelineCalculationService stateless service for computing predicted clock times (E08S03)
     * @param objectMapper               Jackson mapper for draft JSON serialization/deserialization
     */
    public DraftService(TournamentRepository tournamentRepository,
                        PhaseRepository phaseRepository,
                        PhaseBreakRepository phaseBreakRepository,
                        TeamRepository teamRepository,
                        TeamAvatarRepository teamAvatarRepository,
                        TimelineCalculationService timelineCalculationService,
                        ObjectMapper objectMapper) {
        this.tournamentRepository = tournamentRepository;
        this.phaseRepository = phaseRepository;
        this.phaseBreakRepository = phaseBreakRepository;
        this.teamRepository = teamRepository;
        this.teamAvatarRepository = teamAvatarRepository;
        this.timelineCalculationService = timelineCalculationService;
        this.objectMapper = objectMapper;
    }

    // -------------------------------------------------------------------------
    // AC2 — Save draft
    // -------------------------------------------------------------------------

    /**
     * Saves the draft configuration for a tournament (AC2).
     *
     * <p>Only DRAFT-status tournaments may have their draft modified. If the tournament is
     * in PLANNED or any other non-DRAFT status, a {@link ConflictException} is thrown.
     *
     * @param tournamentId the tournament UUID
     * @param config       the draft configuration to persist
     * @return the saved draft configuration (identical to input if validation passes)
     * @throws NoSuchElementException if the tournament does not exist for the current tenant
     * @throws ConflictException      if the tournament is not in DRAFT status (AC2)
     * @throws IllegalArgumentException if any section fails field validation (AC1)
     * @throws IllegalStateException  if no tenant context is active
     */
    public DraftConfig saveDraft(UUID tournamentId, DraftConfig config) {
        Tournament tournament = loadTournamentOrThrow(tournamentId);
        requireDraftStatus(tournament, "modify the draft of");

        // Count participating teams (needed for break position validation — AC8, AC9 — E08S05)
        int participatingTeamCount = (int) teamRepository.findByTournamentId(tournamentId).stream()
                .filter(Team::isParticipate)
                .count();

        // Validate all sections (AC1 field constraints + AC8/AC9 break semantics — E08S05)
        for (DraftSection section : config.getSections()) {
            section.validate();
            // Validate break positions against computed lap count (AC8, AC9)
            if (!section.getBreaks().isEmpty()) {
                int totalLaps = computeTotalLaps(section, participatingTeamCount);
                section.validateBreaks(totalLaps);
            }
        }

        tournament.setDraftJson(serializeConfig(config));
        tournamentRepository.save(tournament);
        return config;
    }

    // -------------------------------------------------------------------------
    // AC3 — Get draft
    // -------------------------------------------------------------------------

    /**
     * Returns the current draft configuration for a tournament (AC3).
     *
     * <p>Returns an empty {@link DraftConfig} (no sections) if no draft has been saved yet.
     * Accessible for any tournament status — the organizer may need to view what was applied.
     *
     * @param tournamentId the tournament UUID
     * @return the current draft configuration; never {@code null}
     * @throws NoSuchElementException if the tournament does not exist for the current tenant
     * @throws IllegalStateException  if no tenant context is active
     */
    public DraftConfig getDraft(UUID tournamentId) {
        Tournament tournament = loadTournamentOrThrow(tournamentId);
        return deserializeOrEmpty(tournament.getDraftJson());
    }

    // -------------------------------------------------------------------------
    // AC4 — Preview draft
    // -------------------------------------------------------------------------

    /**
     * Calculates a preview of what the draft will produce without creating any entities (AC4).
     *
     * <p>For each section, computes: phase number, group count, teams per group,
     * matches per group (round-robin), total laps, total matches, and estimated time.
     *
     * <p>Preview requires at least one section in the draft; otherwise throws
     * {@link IllegalArgumentException}.
     *
     * @param tournamentId the tournament UUID
     * @return ordered list of preview sections; never {@code null}
     * @throws NoSuchElementException   if the tournament does not exist for the current tenant
     * @throws IllegalArgumentException if the draft has no sections (nothing to preview)
     * @throws IllegalStateException    if no tenant context is active
     */
    public DraftPreviewResult previewDraft(UUID tournamentId) {
        Tournament tournament = loadTournamentOrThrow(tournamentId);
        DraftConfig config = deserializeOrEmpty(tournament.getDraftJson());

        if (config.getSections().isEmpty()) {
            throw new IllegalArgumentException(
                    "Cannot preview an empty draft — add at least one section first.");
        }

        // Count participating teams for this tournament
        int participatingTeamCount = (int) teamRepository.findByTournamentId(tournamentId).stream()
                .filter(Team::isParticipate)
                .count();

        List<DraftPreviewSection> previews = new ArrayList<>();
        List<PhaseConfig> phaseConfigs = new ArrayList<>();

        for (DraftSection section : config.getSections()) {
            DraftPreviewSection preview = computePreview(section, participatingTeamCount);
            previews.add(preview);

            // Build PhaseConfig for timeline calculation (AC3 — E08S05)
            List<PhaseBreakConfig> breakConfigs = section.getBreaks().stream()
                    .map(b -> new PhaseBreakConfig(b.getAfterLapNumber(), b.getDurationMinutes(), b.getLabel()))
                    .toList();
            phaseConfigs.add(new PhaseConfig(
                    section.getSectionNumber(),
                    preview.getTotalLaps(),
                    section.getLapTimeMinutes(),
                    section.getLapBreakTimeMinutes(),
                    breakConfigs
            ));
        }

        // Compute timeline if plannedStartTime is set (AC3 — E08S05); otherwise empty list
        // sectionBreakMinutes: use the last section's sectionBreakTimeMinutes as a shared value
        // (section break is defined per-section but applies between sections — use first section's value
        // or 0 if only one section)
        int sectionBreakMinutes = config.getSections().size() > 1
                ? config.getSections().get(0).getSectionBreakTimeMinutes()
                : 0;
        List<TimelineEntry> timeline = timelineCalculationService.calculate(
                tournament.getPlannedStartTime(), phaseConfigs, sectionBreakMinutes);

        return new DraftPreviewResult(previews, timeline);
    }

    // -------------------------------------------------------------------------
    // AC5, AC6, AC7 — Apply draft
    // -------------------------------------------------------------------------

    /**
     * Applies the draft configuration to create Phase entities and distribute Phase 1
     * TeamAvatars (AC5–AC7).
     *
     * <h2>Preconditions (AC5, AC11, AC12)</h2>
     * <ul>
     *   <li>Tournament must be in DRAFT status (409 if not)</li>
     *   <li>Draft must have at least 1 section (400 / 409 per AC5)</li>
     *   <li>At least 2 participating teams must exist (400 / 409 per AC5)</li>
     *   <li>No phases may already exist for this tournament (409 per AC12)</li>
     *   <li>setQuantity for each section must be compatible with the tournament's matchFormat (400)</li>
     * </ul>
     *
     * <h2>Phase 1 TeamAvatar distribution (AC6)</h2>
     * <p>Participating teams sorted by teamNumber (ascending). Teams are distributed round-robin:
     * team[i] → group = (i % groupCount) + 1, position = (i / groupCount) + 1.
     * sortTypes {@code placement_group} and {@code group_placement} fall back to
     * {@code team_number} ordering for Phase 1 (AC6).
     *
     * <h2>Status transition (AC7)</h2>
     * <p>Tournament transitions from DRAFT to PLANNED after successful apply. The draft can
     * no longer be modified after this point.
     *
     * @param tournamentId the tournament UUID
     * @return ordered list of created Phase IDs (one per section), never empty
     * @throws NoSuchElementException   if the tournament does not exist for the current tenant
     * @throws ConflictException        if tournament is not DRAFT, has existing phases, or preconditions not met
     * @throws IllegalArgumentException if draft has no sections, <2 participating teams, or invalid setQuantity
     * @throws IllegalStateException    if no tenant context is active
     */
    public List<UUID> applyDraft(UUID tournamentId) {
        Tournament tournament = loadTournamentOrThrow(tournamentId);
        requireDraftStatus(tournament, "apply the draft of");

        DraftConfig config = deserializeOrEmpty(tournament.getDraftJson());

        // AC5 / AC11: validate preconditions
        if (config.getSections().isEmpty()) {
            throw new ConflictException(
                    "Cannot apply draft for tournament '" + tournamentId
                    + "': the draft has no sections. Add at least one section before applying.");
        }

        // AC12: re-apply prevention
        List<Phase> existingPhases = phaseRepository.findByTournamentId(tournamentId);
        if (!existingPhases.isEmpty()) {
            throw new ConflictException(
                    "Cannot apply draft for tournament '" + tournamentId
                    + "': tournament already has " + existingPhases.size()
                    + " phase(s). The draft can only be applied once.");
        }

        // Validate setQuantity for each section against tournament matchFormat (AC11)
        MatchFormat matchFormat = MatchFormat.fromPersistedName(tournament.getMatchFormat());
        for (DraftSection section : config.getSections()) {
            validateSetQuantity(section, matchFormat);
        }

        // Get participating teams sorted by teamNumber (AC6)
        List<Team> participatingTeams = teamRepository.findByTournamentId(tournamentId).stream()
                .filter(Team::isParticipate)
                .sorted(Comparator.comparingInt(Team::getTeamNumber))
                .toList();

        if (participatingTeams.size() < 2) {
            throw new ConflictException(
                    "Cannot apply draft for tournament '" + tournamentId
                    + "': at least 2 participating teams are required, but only "
                    + participatingTeams.size() + " found.");
        }

        // Create phases, distribute Phase 1 TeamAvatars, and persist PhaseBreak entities
        List<UUID> createdPhaseIds = new ArrayList<>();
        List<DraftSection> sections = config.getSections();

        for (int i = 0; i < sections.size(); i++) {
            DraftSection section = sections.get(i);
            boolean isFirstPhase = (i == 0);

            Phase phase = new Phase(
                    UUID.randomUUID(),
                    null,                                   // tenantId — set by repository
                    tournamentId,
                    section.getSectionNumber(),             // sequenceNumber
                    "Phase " + section.getSectionNumber(),  // human label
                    Phase.PhaseStatus.PENDING.name(),
                    0,                                      // currentLapNumber
                    java.time.LocalDateTime.now()           // createdAt — DB default not set by Spring Data JDBC
            );
            phase = phaseRepository.save(phase);
            createdPhaseIds.add(phase.getId());

            // AC6: distribute Phase 1 TeamAvatars only
            if (isFirstPhase) {
                distributePhase1TeamAvatars(phase, section, participatingTeams, tournamentId);
            }

            // AC7 (E08S05): persist intra-phase breaks as PhaseBreak entities
            persistPhaseBreaks(phase.getId(), section.getBreaks());
        }

        // AC7: transition tournament DRAFT → PLANNED, clear draft_json (phases are now source of truth)
        tournament.setStatus(STATUS_PLANNED);
        tournament.setDraftJson(null);
        tournamentRepository.save(tournament);

        return createdPhaseIds;
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    /**
     * Distributes participating teams into Phase 1 groups using round-robin assignment (AC6).
     *
     * <p>Teams are sorted by teamNumber. For each team[i]:
     * <ul>
     *   <li>groupNumber = (i % groupCount) + 1</li>
     *   <li>groupPosition = (i / groupCount) + 1</li>
     * </ul>
     *
     * @param phase              the newly created Phase 1
     * @param section            the first section configuration (groupCount, sortType)
     * @param participatingTeams participating teams sorted by teamNumber ascending
     * @param tournamentId       the parent tournament
     */
    private void distributePhase1TeamAvatars(Phase phase, DraftSection section,
                                              List<Team> participatingTeams, UUID tournamentId) {
        int groupCount = section.getGroupCount();
        for (int i = 0; i < participatingTeams.size(); i++) {
            Team team = participatingTeams.get(i);
            int groupNumber = (i % groupCount) + 1;
            int groupPosition = (i / groupCount) + 1;

            TeamAvatar avatar = new TeamAvatar(
                    UUID.randomUUID(),
                    null,          // tenantId — set by repository
                    tournamentId,
                    phase.getId(),
                    groupNumber,
                    groupPosition,
                    team.getId(),
                    null,          // description — optional, omit for now
                    java.time.LocalDateTime.now()  // createdAt — DB default not set by Spring Data JDBC
            );
            teamAvatarRepository.save(avatar);
        }
    }

    /**
     * Calculates the preview for one section given the participating team count (AC4).
     *
     * @param section                the section to preview
     * @param participatingTeamCount number of participating teams in the tournament
     * @return the computed preview section
     */
    private DraftPreviewSection computePreview(DraftSection section, int participatingTeamCount) {
        int groupCount = section.getGroupCount();
        // Integer division (floor) — last group may have fewer teams
        int teamsPerGroup = participatingTeamCount / groupCount;
        // Round-robin: each pair plays exactly once
        int matchesPerGroup = teamsPerGroup > 1 ? teamsPerGroup * (teamsPerGroup - 1) / 2 : 0;
        int totalLaps = teamsPerGroup > 1 ? teamsPerGroup - 1 : 0;
        int totalMatches = groupCount * matchesPerGroup;

        // Estimated time: laps × lapTime + inter-lap breaks + intra-phase break durations + section break
        int interLapBreaks = Math.max(0, totalLaps - 1) * section.getLapBreakTimeMinutes();
        int lapTime = totalLaps * section.getLapTimeMinutes();
        int intraPhaseBreakTime = section.getBreaks().stream()
                .mapToInt(DraftBreak::getDurationMinutes)
                .sum();
        int estimatedTimeMinutes = lapTime + interLapBreaks + intraPhaseBreakTime
                + section.getSectionBreakTimeMinutes();

        return new DraftPreviewSection(
                section.getSectionNumber(),
                groupCount,
                teamsPerGroup,
                matchesPerGroup,
                totalLaps,
                totalMatches,
                estimatedTimeMinutes
        );
    }

    /**
     * Computes the total number of laps for a section given the participating team count.
     *
     * <p>Used to validate break positions (AC8 — E08S05) before the full preview is computed.
     *
     * @param section                the section configuration
     * @param participatingTeamCount total participating teams in the tournament
     * @return total laps (rounds) for this section; 0 if fewer than 2 teams per group
     */
    private int computeTotalLaps(DraftSection section, int participatingTeamCount) {
        int teamsPerGroup = participatingTeamCount / section.getGroupCount();
        return teamsPerGroup > 1 ? teamsPerGroup - 1 : 0;
    }

    /**
     * Persists each {@link DraftBreak} from a section as a {@link PhaseBreak} entity linked
     * to the given phase (AC7 — E08S05).
     *
     * <p>Breaks are not validated here — break validation occurs in {@link #saveDraft} and
     * during apply preconditions. This method performs only the persistence step.
     *
     * @param phaseId the UUID of the newly created Phase
     * @param breaks  the list of breaks from the corresponding draft section; may be empty
     */
    private void persistPhaseBreaks(UUID phaseId, List<DraftBreak> breaks) {
        for (DraftBreak draftBreak : breaks) {
            PhaseBreak phaseBreak = new PhaseBreak(
                    UUID.randomUUID(),
                    null,                            // tenantId — set by TenantScopedRepository.save()
                    phaseId,
                    draftBreak.getAfterLapNumber(),
                    draftBreak.getDurationMinutes(),
                    draftBreak.getLabel()
            );
            phaseBreakRepository.save(phaseBreak);
        }
    }

    /**
     * Validates that the section's setQuantity is compatible with the tournament matchFormat (AC11).
     *
     * <p>setQuantity must be ≤ matchFormat.maxSets. For FIXED_2_SETS, setQuantity must equal 2.
     * For BEST_OF_N formats, setQuantity must equal the format's maxSets (all sets are played
     * in a structured bracket — the match format dictates the set count).
     *
     * @param section     the section to validate
     * @param matchFormat the tournament's match format
     * @throws IllegalArgumentException if setQuantity is incompatible with the format
     */
    private void validateSetQuantity(DraftSection section, MatchFormat matchFormat) {
        int setQuantity = section.getSetQuantity();
        int maxSets = matchFormat.getMaxSets();
        if (setQuantity > maxSets) {
            throw new IllegalArgumentException(
                    "Section " + section.getSectionNumber() + ": setQuantity " + setQuantity
                    + " exceeds the maximum allowed sets for matchFormat "
                    + matchFormat.name() + " (maxSets=" + maxSets + ").");
        }
        if (setQuantity < 1) {
            throw new IllegalArgumentException(
                    "Section " + section.getSectionNumber() + ": setQuantity must be ≥ 1, got "
                    + setQuantity + ".");
        }
    }

    /**
     * Loads the tournament by ID for the active tenant, throwing {@link NoSuchElementException}
     * if not found.
     *
     * @param tournamentId the tournament UUID
     * @return the tournament (never {@code null})
     */
    private Tournament loadTournamentOrThrow(UUID tournamentId) {
        return tournamentRepository.findById(tournamentId)
                .orElseThrow(() -> new NoSuchElementException(
                        "Tournament not found: " + tournamentId));
    }

    /**
     * Asserts that the tournament is in DRAFT status, throwing {@link ConflictException} if not.
     *
     * @param tournament the tournament to check
     * @param action     human-readable action description for the error message
     * @throws ConflictException if the tournament is not in DRAFT status
     */
    private void requireDraftStatus(Tournament tournament, String action) {
        if (!STATUS_DRAFT.equals(tournament.getStatus())) {
            throw new ConflictException(
                    "Cannot " + action + " tournament '" + tournament.getId()
                    + "': only DRAFT tournaments support this operation. Current status: "
                    + tournament.getStatus() + ".");
        }
    }

    /**
     * Serializes a {@link DraftConfig} to JSON, wrapping {@link JsonProcessingException} as
     * {@link IllegalStateException} (a configuration failure, not a recoverable error).
     *
     * @param config the config to serialize
     * @return JSON string
     */
    private String serializeConfig(DraftConfig config) {
        try {
            return objectMapper.writeValueAsString(config);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Failed to serialize DraftConfig to JSON", ex);
        }
    }

    /**
     * Deserializes a JSON string into a {@link DraftConfig}, returning {@link DraftConfig#empty()}
     * if the JSON is {@code null} or blank (AC3 — no draft yet).
     *
     * @param json the draft JSON or {@code null}
     * @return the deserialized config; never {@code null}
     */
    private DraftConfig deserializeOrEmpty(String json) {
        if (json == null || json.isBlank()) {
            return DraftConfig.empty();
        }
        try {
            return objectMapper.readValue(json, DraftConfig.class);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException(
                    "Failed to deserialize draft JSON from database. "
                    + "The stored draft may be corrupt.", ex);
        }
    }
}
