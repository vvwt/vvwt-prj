package de.vvwt.tm.tournament.internal;

import com.fasterxml.jackson.databind.ObjectMapper;
import de.vvwt.tm.tournament.DraftService;
import de.vvwt.tm.tournament.Phase;
import de.vvwt.tm.tournament.PhaseBreak;
import de.vvwt.tm.tournament.PhaseBreakConfig;
import de.vvwt.tm.tournament.PhaseBreakRepository;
import de.vvwt.tm.tournament.PhaseConfig;
import de.vvwt.tm.tournament.PhaseRepository;
import de.vvwt.tm.tournament.TimelineCalculationService;
import de.vvwt.tm.tournament.TimelineEntry;
import de.vvwt.tm.tournament.Tournament;
import de.vvwt.tm.tournament.TournamentLifecycleService;
import de.vvwt.tm.tournament.TournamentRepository;
import de.vvwt.tm.tournament.draft.DraftBreak;
import de.vvwt.tm.tournament.draft.DraftConfig;
import de.vvwt.tm.tournament.draft.DraftPreviewResult;
import de.vvwt.tm.tournament.draft.DraftPreviewSection;
import de.vvwt.tm.tournament.draft.DraftSection;
import de.vvwt.tm.tournament.exceptions.ConflictException;
import de.vvwt.tm.tournament.exceptions.TournamentNotFoundException;
import de.vvwt.tm.tournament.exceptions.TournamentNotInDraftException;
import de.vvwt.tm.tournament.exceptions.TournamentResetPlanActiveException;
import de.vvwt.tm.tournament.exceptions.TournamentResetPlanCancelledException;
import de.vvwt.tm.tournament.exceptions.TournamentResetPlanCompletedException;
import de.vvwt.tm.tournament.exceptions.TournamentResetPlanDraftIdempotentException;
import de.vvwt.tm.tournament.internal.dto.draft.DraftTimelineEntryResponse;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Default implementation of {@link DraftService}.
 *
 * <p>Domain service for draft configuration preview and apply operations.
 *
 * <h2>Scoped API</h2>
 *
 * <ul>
 *   <li>{@link #preview(DraftConfig, int, int, LocalTime)} — pure computation, no DB side effect;
 *       populates timeline when {@code plannedStartTime} is non-null (E48S12)
 *   <li>{@link #apply(UUID, DraftConfig)} — atomic six-step operation: (a) DRAFT-precondition
 *       check, (b+c) invariant validation, (d) Phase record creation (PENDING, no TeamAvatars), (e)
 *       draft_json persist, (f) DRAFT→PLANNED delegation to {@link
 *       TournamentLifecycleService#markPlanned} — all in one {@code @Transactional} boundary
 *       (E48S22, AC-IMPL-APPLY-FOUR-OPS-ATOMIC)
 *   <li>{@link #loadDraft(UUID)} — loads current draft config from Tournament.draftJson (E21S19)
 *   <li>{@link #saveDraft(UUID, DraftConfig)} — persists draft config to Tournament.draftJson
 *       (E21S19)
 * </ul>
 *
 * <h2>DRAFT-precondition (E48S22, AC-IMPL-PHASES-EXIST-GUARD-REMOVED)</h2>
 *
 * <p>The service <strong>fails-fast</strong> on non-DRAFT tournaments: {@code apply()} acquires the
 * per-tournament row-lock (DEC-37 Clause B first-read), then checks {@code status == "DRAFT"}. If
 * the status is anything else (PLANNED, ACTIVE, COMPLETED, CANCELLED), {@link
 * TournamentNotInDraftException} is thrown (→ 409 Conflict with messageKey {@code
 * draft.error.notInDraftStatus}). The former phases-exist guard (AC-DRAFT-APPLY-IDEMPOTENCY) is
 * subsumed: under the new atomic-apply invariant, phases exist iff status=PLANNED, so the
 * DRAFT-precondition at step (a) is sufficient.
 *
 * <h2>Reconstruction-in-place (DEC-21/DEC-22)</h2>
 *
 * <p>Legacy {@code de.vvwt.tm.domain.DraftService} remains active until E21S13 atomic cutover
 * (DEC-32). This service uses the qualifier {@code "tmDraftService"} to avoid bean-name conflict.
 *
 * <p>Inventory: E21S01 line 171.
 *
 * @see DraftService
 * @see DraftConfig
 * @see DraftPreviewResult
 * @see Phase
 * @see PhaseRepository
 * @see <a href="DEC-21">DEC-21 — Spring Modulith package layout</a>
 * @see <a href="DEC-22">DEC-22 — TDD reconstruction-in-place</a>
 * @see <a href="DEC-35">DEC-35 — interface in public package, impl in internal</a>
 * @see <a href="E21S07">E21S07 — Draft phase-planning reconstruction</a>
 * @see <a href="E33S05">E33S05 — DraftService interface extraction (DEC-35 retrofit)</a>
 * @see <a href="E21S19">E21S19 — Restore GET/PUT mappings: loadDraft + saveDraft</a>
 * @see <a href="E48S12">E48S12 — Wire TimelineCalculationService into preview()</a>
 */
@Service("tmDraftService")
public class DefaultDraftService implements DraftService {

    private final PhaseRepository phaseRepository;
    private final PhaseBreakRepository phaseBreakRepository;
    private final TournamentRepository tournamentRepository;
    private final ObjectMapper objectMapper;
    private final TimelineCalculationService timelineCalculationService;
    private final JdbcTemplate jdbcTemplate;
    private final TournamentLifecycleService lifecycleService;

    /**
     * Constructs the service with Phase-aggregate collaborators from E21S03, tournament repository
     * + Jackson ObjectMapper for draft JSON serialization (E21S19), the timeline calculation
     * service for preview timeline population (E48S12), JdbcTemplate for cascade-delete and
     * reset-plan bulk SQL (E48S13), and the {@link TournamentLifecycleService} for DRAFT→PLANNED
     * delegation in {@link #apply} (E48S22, DEC-35 authority-locality).
     *
     * <p>Phase-1 TeamAvatar distribution and match generation were removed in E48S17: apply() now
     * only creates Phase records in PENDING status. TeamAvatar creation is deferred to the
     * Drag&amp;Drop / prepare() workflow.
     *
     * @param phaseRepository phase persistence (tenant-scoped, E21S03)
     * @param phaseBreakRepository phase break persistence (tenant-scoped, E21S03)
     * @param tournamentRepository tournament persistence for draft JSON read/write (E21S19)
     * @param objectMapper Jackson ObjectMapper for DraftConfig ↔ JSON round-trip (E21S19)
     * @param timelineCalculationService stateless timeline engine (E21S11, wired in E48S12)
     * @param jdbcTemplate JDBC template for bulk cascade SQL (E48S13 AC-IMPL-CASCADE-DELETE-HELPER)
     * @param lifecycleService tournament lifecycle service for DRAFT→PLANNED delegation in apply()
     *     (E48S22, DEC-35 AC-GOVERNANCE-DEC-35-AUTHORITY-LOCALITY)
     */
    public DefaultDraftService(
            @Qualifier("tmPhaseRepository") PhaseRepository phaseRepository,
            @Qualifier("tmPhaseBreakRepository") PhaseBreakRepository phaseBreakRepository,
            @Qualifier("tmTournamentRepository") TournamentRepository tournamentRepository,
            ObjectMapper objectMapper,
            @Qualifier("tmTimelineCalculationService")
                    TimelineCalculationService timelineCalculationService,
            JdbcTemplate jdbcTemplate,
            TournamentLifecycleService lifecycleService) {
        this.phaseRepository = phaseRepository;
        this.phaseBreakRepository = phaseBreakRepository;
        this.tournamentRepository = tournamentRepository;
        this.objectMapper = objectMapper;
        this.timelineCalculationService = timelineCalculationService;
        this.jdbcTemplate = jdbcTemplate;
        this.lifecycleService = lifecycleService;
    }

    // -------------------------------------------------------------------------
    // preview — pure computation
    // -------------------------------------------------------------------------

    /**
     * Calculates a preview of what the draft will produce without creating any entities.
     *
     * <p>For each section, computes: phase number, group count, teams per group, matches per group
     * (round-robin), total laps (field-count-aware per E48S10), total matches, and estimated
     * duration.
     *
     * <p>When {@code plannedStartTime} is non-null, this method additionally computes a full
     * timeline via {@link TimelineCalculationService} (E48S12, AC-IMPL-DRAFT-SERVICE-PREVIEW-WIRES-
     * TIMELINE-SERVICE). The timeline is built using Strategy (i): per-phase calls to {@code
     * calculate()}, with per-section {@code sectionBreakTimeMinutes} applied between consecutive
     * phases by manually appending {@link de.vvwt.tm.tournament.TimelineEntryType#SECTION_BREAK}
     * entries. When {@code plannedStartTime} is {@code null} or {@code sections} is empty, the
     * timeline is {@code List.of()} (AC-ERROR-HANDLING-NULL-SAFETY).
     *
     * <p>The {@code fieldCount} parameter is threaded from {@code tournament.getFieldCount()} by
     * the controller (E48S10, AC-IMPL-DRAFT-CONTROLLER-LOAD-FIELDCOUNT). Values ≤ 0 are clamped to
     * 1 by the formula (AC-ERROR-HANDLING-FIELDCOUNT-CLAMP).
     *
     * @param config the draft configuration to preview; must not be {@code null}
     * @param participatingTeamCount number of participating teams
     * @param fieldCount number of available fields; values ≤ 0 are clamped to 1
     * @param plannedStartTime optional tournament start time; {@code null} → empty timeline
     * @return preview result; never {@code null}
     * @see <a href="E48S10">E48S10 — AC-IMPL-COMPUTE-PREVIEW-FIELD-AWARE-FORMULA</a>
     * @see <a href="E48S12">E48S12 — AC-IMPL-DRAFT-SERVICE-PREVIEW-WIRES-TIMELINE-SERVICE</a>
     */
    @Override
    public DraftPreviewResult preview(
            DraftConfig config,
            int participatingTeamCount,
            int fieldCount,
            LocalTime plannedStartTime) {
        List<DraftPreviewSection> previews = new ArrayList<>();
        for (DraftSection section : config.getSections()) {
            previews.add(computePreview(section, participatingTeamCount, fieldCount));
        }
        List<DraftTimelineEntryResponse> timeline =
                buildTimeline(config.getSections(), previews, plannedStartTime);
        return new DraftPreviewResult(previews, timeline);
    }

    // -------------------------------------------------------------------------
    // apply — phase creation
    // -------------------------------------------------------------------------

    /**
     * Atomically applies the draft configuration in six steps (E48S22,
     * AC-IMPL-APPLY-FOUR-OPS-ATOMIC).
     *
     * <ol>
     *   <li>(a) Acquires per-tournament DB row-lock via {@link
     *       TournamentRepository#findByIdForUpdate(UUID)} as the FIRST read (DEC-37 Clause B).
     *   <li>(b) DRAFT-precondition check: if {@code tournament.status != "DRAFT"}, throws {@link
     *       TournamentNotInDraftException} (→ 409 with messageKey {@code
     *       draft.error.notInDraftStatus}).
     *   <li>(c) Invariant validation: {@link
     *       de.vvwt.tm.tournament.draft.DraftConfig#validateFirstPhaseTeamNumber()} + {@link
     *       de.vvwt.tm.tournament.draft.DraftConfig#validateLastPhaseSiegerehrung()}.
     *   <li>(d) Phase record creation: one Phase per section in PENDING status; PhaseBreak entities
     *       for intra-phase breaks. No TeamAvatars or matches (E48S17
     *       AC-IMPL-APPLY-NO-PHASE-1-TEAMAVATARS).
     *   <li>(e) Persists draft_json: {@code tournament.setDraftJson(serialized config)} +
     *       repository save (makes draft_json available to loadDraft() post-Apply).
     *   <li>(f) Delegates DRAFT→PLANNED transition to {@link
     *       TournamentLifecycleService#markPlanned(UUID)} (DEC-35
     *       AC-GOVERNANCE-DEC-35-AUTHORITY-LOCALITY — NOT an inline setStatus call here). {@code
     *       markPlanned()} runs under REQUIRED propagation and re-acquires the row-lock on the same
     *       row — re-entrant under H2 (same TX, no-op acquire per H2 lock semantics).
     * </ol>
     *
     * <p>All six steps execute inside a single {@code @Transactional} boundary. Any exception from
     * any step rolls back the entire transaction — zero Phase rows, unchanged draft_json, unchanged
     * status (AC-TEST-APPLY-ATOMICITY-ROLLBACK-RED).
     *
     * @param tournamentId the tournament UUID
     * @param config the draft configuration to apply; must not be {@code null}
     * @return ordered list of created Phase IDs (one per section); never empty
     * @throws TournamentNotInDraftException if the tournament's current status is not {@code DRAFT}
     *     (AC-ERROR-HANDLING-NON-DRAFT-STATUS-TYPED-EXCEPTION, AC-IMPL-PHASES-EXIST-GUARD-REMOVED)
     */
    @Transactional
    @Override
    public List<UUID> apply(UUID tournamentId, DraftConfig config) {
        // Step (a): DEC-37 Clause B — acquire per-tournament row-lock as the FIRST read
        Tournament tournament = tournamentRepository.findByIdForUpdate(tournamentId);

        // Step (b): DRAFT-precondition check
        String status = tournament.getStatus();
        if (!"DRAFT".equals(status)) {
            throw new TournamentNotInDraftException(tournamentId, status);
        }

        // Step (c): Invariant validation
        // AC-IMPL-FIRST-PHASE-INVARIANT (E48S16): first phase must have sortType=team_number
        config.validateFirstPhaseTeamNumber();
        // AC-IMPL-LAST-PHASE-INVARIANT (E48S01): D-10 — last phase must be siegerehrung
        config.validateLastPhaseSiegerehrung();

        // Step (d): Phase record creation (PENDING status, no TeamAvatars — E48S17)
        List<UUID> createdPhaseIds = new ArrayList<>();
        List<DraftSection> sections = config.getSections();

        for (DraftSection section : sections) {
            Phase phase =
                    new Phase(
                            UUID.randomUUID(),
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

        // Step (e): Persist draft_json (makes loadDraft() return the applied config post-Apply)
        try {
            tournament.setDraftJson(objectMapper.writeValueAsString(config));
        } catch (Exception e) {
            throw new IllegalStateException(
                    "Failed to serialize draft config for tournament " + tournamentId, e);
        }
        tournamentRepository.save(tournament);

        // Step (f): Delegate DRAFT→PLANNED transition to TournamentLifecycleService (DEC-35)
        // AC-GOVERNANCE-DEC-35-AUTHORITY-LOCALITY: transition via lifecycle service, NOT inline
        // setStatus(). markPlanned() runs under REQUIRED propagation — joins the outer TX.
        lifecycleService.markPlanned(tournamentId);

        return createdPhaseIds;
    }

    // -------------------------------------------------------------------------
    // loadDraft — read draft configuration (E21S19 AC-TEST-GET-EMPTY-RED)
    // -------------------------------------------------------------------------

    /**
     * Loads the current draft configuration for a tournament.
     *
     * <p>Returns {@link DraftConfig#empty()} if no draft has been saved ({@code
     * Tournament.draftJson IS NULL}). Returns the deserialized config if a draft was previously
     * saved via {@link #saveDraft}.
     *
     * <p>Tenant scoping enforced at the repository layer via TenantContext (DEC-20).
     *
     * @param tournamentId the tournament UUID
     * @return current draft config; never {@code null}; may have empty sections list
     * @throws TournamentNotFoundException if the tournament does not exist in the current tenant
     * @see <a href="E21S19">E21S19 — AC-TEST-GET-EMPTY-RED,
     *     AC-TEST-GET-WITH-DATA-ROUND-TRIP-RED</a>
     */
    @Override
    public DraftConfig loadDraft(UUID tournamentId) {
        Tournament tournament =
                tournamentRepository
                        .findById(tournamentId)
                        .orElseThrow(() -> new TournamentNotFoundException(tournamentId));
        String json = tournament.getDraftJson();
        if (json == null || json.isBlank()) {
            return DraftConfig.empty();
        }
        try {
            return objectMapper.readValue(json, DraftConfig.class);
        } catch (Exception e) {
            throw new IllegalStateException(
                    "Failed to deserialize draft config for tournament " + tournamentId, e);
        }
    }

    // -------------------------------------------------------------------------
    // saveDraft — persist draft configuration (E21S19 AC-TEST-PUT-SUCCESS-RED)
    // -------------------------------------------------------------------------

    /**
     * Saves the draft configuration for a tournament in {@code DRAFT} status.
     *
     * <p>Only tournaments in {@code DRAFT} status may have their draft configuration saved.
     * Attempts to save for non-{@code DRAFT} tournaments throw a 409 {@link ConflictException}.
     *
     * <p>Tenant scoping enforced at the repository layer via TenantContext (DEC-20).
     *
     * @param tournamentId the tournament UUID
     * @param config the draft configuration to save; must not be {@code null}
     * @return the saved draft configuration (round-trip read from persistence); never {@code null}
     * @throws TournamentNotFoundException if the tournament does not exist in the current tenant
     * @throws ConflictException if the tournament is not in {@code DRAFT} status
     * @see <a href="E21S19">E21S19 — AC-TEST-PUT-SUCCESS-RED, AC-TEST-PUT-NON-DRAFT-409-RED</a>
     */
    @Override
    public DraftConfig saveDraft(UUID tournamentId, DraftConfig config) {
        Tournament tournament =
                tournamentRepository
                        .findById(tournamentId)
                        .orElseThrow(() -> new TournamentNotFoundException(tournamentId));
        if (!"DRAFT".equals(tournament.getStatus())) {
            throw new ConflictException(
                    "Draft cannot be modified: tournament '"
                            + tournamentId
                            + "' is in status "
                            + tournament.getStatus()
                            + " (only DRAFT tournaments may be saved).");
        }
        String json;
        try {
            json = objectMapper.writeValueAsString(config);
        } catch (Exception e) {
            throw new IllegalStateException(
                    "Failed to serialize draft config for tournament " + tournamentId, e);
        }
        tournament.setDraftJson(json);
        tournamentRepository.save(tournament);
        return loadDraft(tournamentId);
    }

    // -------------------------------------------------------------------------
    // resetPlan — reset Phasenplan from PLANNED back to DRAFT (E48S13)
    // -------------------------------------------------------------------------

    /**
     * Resets the Phasenplan for a {@code PLANNED} tournament back to {@code DRAFT} (E48S13,
     * AC-IMPL-RESET-PLAN-OP).
     *
     * <p>DEC-37 Clause B: {@code findByIdForUpdate} is the first read (pessimistic lock). Status
     * guard: PLANNED only — all other statuses throw a typed {@link ConflictException} subclass.
     * Then: invokes {@link #cascadeDeleteStructuralData(UUID)}; issues {@code UPDATE tournament SET
     * status = 'DRAFT' WHERE id = ?}. Does NOT modify {@code draftJson}, {@code team}, {@code
     * activity_types}, or {@code certificate_template}.
     *
     * @param tournamentId the tournament UUID
     * @return the updated tournament with {@code status = 'DRAFT'}; never {@code null}
     * @throws TournamentNotFoundException if the tournament does not exist in the current tenant
     * @throws TournamentResetPlanActiveException if status is ACTIVE
     * @throws TournamentResetPlanCancelledException if status is CANCELLED
     * @throws TournamentResetPlanCompletedException if status is COMPLETED
     * @throws TournamentResetPlanDraftIdempotentException if status is already DRAFT (409 per
     *     AC-TEST-RESET-PLAN-DAO-IT-RED)
     * @see <a href="E48S13">E48S13 — Tournament Admin Escape Hatch</a>
     * @see <a href="DEC-37">DEC-37 — Clause B: findByIdForUpdate first-read for mutation ops</a>
     */
    @Transactional
    @Override
    public Tournament resetPlan(UUID tournamentId) {
        // DEC-37 Clause B: pessimistic lock as first read (throws IllegalArgumentException → 400
        // if not found; TournamentNotFoundException path is handled by the controller via
        // GlobalExceptionHandler mapping IllegalArgumentException → 400)
        Tournament tournament = tournamentRepository.findByIdForUpdate(tournamentId);

        String status = tournament.getStatus();
        switch (status) {
            case "DRAFT" -> throw new TournamentResetPlanDraftIdempotentException(tournamentId);
            case "ACTIVE" -> throw new TournamentResetPlanActiveException(tournamentId);
            case "CANCELLED" -> throw new TournamentResetPlanCancelledException(tournamentId);
            case "COMPLETED" -> throw new TournamentResetPlanCompletedException(tournamentId);
            default -> {
                // PLANNED — proceed with reset
            }
        }

        cascadeDeleteStructuralData(tournamentId);
        jdbcTemplate.update("UPDATE tournament SET status = 'DRAFT' WHERE id = ?", tournamentId);

        return tournamentRepository
                .findById(tournamentId)
                .orElseThrow(() -> new TournamentNotFoundException(tournamentId));
    }

    // -------------------------------------------------------------------------
    // cascadeDeleteStructuralData — shared bulk-delete helper (E48S13)
    // -------------------------------------------------------------------------

    /**
     * Deletes all phase-derived structural data for a tournament in the correct FK-dependency order
     * (E48S13, AC-IMPL-CASCADE-DELETE-HELPER).
     *
     * <p>Executes 9 bulk SQL statements via {@link JdbcTemplate} (no N+1). Does NOT touch the
     * {@code tournament} row, {@code team}, {@code activity_types}, {@code certificate_template},
     * or {@code draftJson}.
     *
     * <p>Step order per Brief D-6a:
     *
     * <ol>
     *   <li>DELETE audit_log WHERE match_id IN (SELECT id FROM match WHERE tournament_id = ?)
     *   <li>DELETE match_outcome WHERE match_id IN (SELECT id FROM match WHERE tournament_id = ?)
     *   <li>DELETE set_result WHERE phase_id IN (SELECT id FROM phase WHERE tournament_id = ?)
     *   <li>DELETE team_avatar_rating WHERE avatar_id IN (SELECT id FROM team_avatar WHERE
     *       tournament_id = ?)
     *   <li>DELETE round_snapshots WHERE tournament_id = ?
     *   <li>DELETE match WHERE tournament_id = ?
     *   <li>DELETE team_avatar WHERE tournament_id = ?
     *   <li>DELETE phase_breaks WHERE phase_id IN (SELECT id FROM phase WHERE tournament_id = ?)
     *   <li>DELETE phase WHERE tournament_id = ?
     * </ol>
     *
     * @param tournamentId the tournament UUID whose structural data should be deleted
     * @see <a href="E48S13">E48S13 — AC-IMPL-CASCADE-DELETE-HELPER</a>
     */
    private void cascadeDeleteStructuralData(UUID tournamentId) {
        // 1. audit_log rows referencing matches of this tournament
        jdbcTemplate.update(
                "DELETE FROM audit_log WHERE match_id IN"
                        + " (SELECT id FROM match WHERE tournament_id = ?)",
                tournamentId);
        // 2. match_outcome rows referencing matches of this tournament
        jdbcTemplate.update(
                "DELETE FROM match_outcome WHERE match_id IN"
                        + " (SELECT id FROM match WHERE tournament_id = ?)",
                tournamentId);
        // 3. set_result rows referencing phases of this tournament
        jdbcTemplate.update(
                "DELETE FROM set_result WHERE phase_id IN"
                        + " (SELECT id FROM phase WHERE tournament_id = ?)",
                tournamentId);
        // 4. team_avatar_rating rows referencing team_avatars of this tournament
        jdbcTemplate.update(
                "DELETE FROM team_avatar_rating WHERE avatar_id IN"
                        + " (SELECT id FROM team_avatar WHERE tournament_id = ?)",
                tournamentId);
        // 5. round_snapshots for this tournament
        jdbcTemplate.update("DELETE FROM round_snapshots WHERE tournament_id = ?", tournamentId);
        // 6. match rows for this tournament
        jdbcTemplate.update("DELETE FROM match WHERE tournament_id = ?", tournamentId);
        // 7. team_avatar rows for this tournament
        jdbcTemplate.update("DELETE FROM team_avatar WHERE tournament_id = ?", tournamentId);
        // 8. phase_breaks rows referencing phases of this tournament
        jdbcTemplate.update(
                "DELETE FROM phase_breaks WHERE phase_id IN"
                        + " (SELECT id FROM phase WHERE tournament_id = ?)",
                tournamentId);
        // 9. phase rows for this tournament
        jdbcTemplate.update("DELETE FROM phase WHERE tournament_id = ?", tournamentId);
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    /**
     * Computes the preview for one section given the participating team count and field count.
     *
     * <h2>Field-count-aware lap formula (E48S10)</h2>
     *
     * <p>A "Runde" (round) is a time-slot where each team plays at most one match. The number of
     * concurrent matches per round is bounded by two constraints:
     *
     * <ul>
     *   <li><b>Team-conflict bound:</b> {@code floor(teamsPerGroup / 2) * groupCount} — at most one
     *       match per team per round; this is the maximum simultaneous matches across all groups.
     *   <li><b>Field bound:</b> {@code fieldCount} — the number of available playing fields.
     * </ul>
     *
     * <p>Formula: {@code effectivePerLap = max(1, min(teamConflictPerLap, fieldCount))}; {@code
     * totalLaps = totalMatches == 0 ? 0 : ceil(totalMatches / effectivePerLap)}.
     *
     * <p>Lower-bound rationale (T-6): the real slot-optimizer may achieve fewer laps via smarter
     * scheduling; the preview is a conservative estimate, not an exact scheduler simulation.
     *
     * @param section the section configuration
     * @param participatingTeamCount total participating teams in the tournament
     * @param fieldCount number of available fields; values ≤ 0 are clamped to 1 via {@code max(1,
     *     ...)}
     * @return the computed preview section
     * @see <a href="E48S10">E48S10 — AC-IMPL-COMPUTE-PREVIEW-FIELD-AWARE-FORMULA</a>
     */
    private DraftPreviewSection computePreview(
            DraftSection section, int participatingTeamCount, int fieldCount) {
        // Siegerehrung branch (E48S09, AC-IMPL-COMPUTE-PREVIEW-SIEGEREHRUNG-BRANCH):
        // SiegerehrungMatchGenerator.generate() returns emptyList() at runtime (E48S02) →
        // preview returns 0 matches/laps. Breaks and sectionBreak preserved for ceremony pause.
        if ("siegerehrung".equals(section.getGameMode())) {
            int intraPhaseBreakTime =
                    section.getBreaks().stream().mapToInt(DraftBreak::getDurationMinutes).sum();
            int estimatedTimeMinutes = intraPhaseBreakTime + section.getSectionBreakTimeMinutes();
            return new DraftPreviewSection(
                    section.getSectionNumber(),
                    section.getGroupCount(),
                    participatingTeamCount / section.getGroupCount(),
                    0, // matchesPerGroup = 0
                    0, // totalLaps = 0
                    0, // totalMatches = 0
                    estimatedTimeMinutes);
        }

        int groupCount = section.getGroupCount();
        int teamsPerGroup = participatingTeamCount / groupCount;
        int matchesPerGroup = teamsPerGroup > 1 ? teamsPerGroup * (teamsPerGroup - 1) / 2 : 0;
        int totalMatches = groupCount * matchesPerGroup;

        // Field-count-aware lap formula (E48S10, AC-IMPL-COMPUTE-PREVIEW-FIELD-AWARE-FORMULA)
        int teamConflictPerLap = (teamsPerGroup / 2) * groupCount;
        int effectivePerLap = Math.max(1, Math.min(teamConflictPerLap, fieldCount));
        int totalLaps =
                totalMatches == 0 ? 0 : (int) Math.ceil((double) totalMatches / effectivePerLap);

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

    // -------------------------------------------------------------------------
    // Timeline building (E48S12)
    // -------------------------------------------------------------------------

    /**
     * Builds the timeline for the preview by invoking {@link TimelineCalculationService} per phase
     * (Strategy i — loop strategy, AC-IMPL-IMPEDANCE-RESOLUTION).
     *
     * <p>Impedance note: {@code TimelineCalculationService.calculate()} accepts a SINGLE {@code
     * sectionBreakMinutes} for ALL phase boundaries, while each {@link DraftSection} carries its
     * own {@code sectionBreakTimeMinutes}. Strategy (i) resolves this by calling {@code
     * calculate()} once per phase with a single-element phase list ({@code sectionBreakMinutes=0}),
     * then appending the per-section {@code SECTION_BREAK} entries manually between consecutive
     * phases.
     *
     * <p>When {@code plannedStartTime == null} or the section list is empty, returns {@code
     * List.of()} immediately (AC-ERROR-HANDLING-NULL-SAFETY).
     *
     * @param sections the draft sections (ordered)
     * @param previews the corresponding computed preview sections (for totalLaps per phase)
     * @param plannedStartTime tournament start time; {@code null} → empty list
     * @return ordered list of {@link DraftTimelineEntryResponse}; never {@code null}
     */
    private List<DraftTimelineEntryResponse> buildTimeline(
            List<DraftSection> sections,
            List<DraftPreviewSection> previews,
            LocalTime plannedStartTime) {
        // AC-ERROR-HANDLING-NULL-SAFETY: null startTime or empty sections → empty timeline
        if (plannedStartTime == null || sections.isEmpty()) {
            return List.of();
        }

        List<DraftTimelineEntryResponse> result = new ArrayList<>();
        LocalTime cursor = plannedStartTime;

        for (int i = 0; i < sections.size(); i++) {
            DraftSection section = sections.get(i);
            DraftPreviewSection preview = previews.get(i);
            boolean isLastSection = (i == sections.size() - 1);

            // Map DraftSection → PhaseConfig (AC-IMPL-DRAFT-CONFIG-TO-PHASE-CONFIG-MAPPING)
            PhaseConfig phaseConfig = toPhaseConfig(section, preview.getTotalLaps(), i + 1);

            // Call calculate() per phase with sectionBreakMinutes=0 (Strategy i)
            List<TimelineEntry> phaseEntries =
                    timelineCalculationService.calculate(cursor, List.of(phaseConfig), 0);

            // Convert TimelineEntry → DraftTimelineEntryResponse and collect
            for (TimelineEntry entry : phaseEntries) {
                result.add(toResponse(entry));
            }

            // Advance cursor to end of last entry in this phase (if any)
            if (!phaseEntries.isEmpty()) {
                cursor = phaseEntries.get(phaseEntries.size() - 1).endTime();
            }

            // Append SECTION_BREAK entry between consecutive phases using per-section break time
            if (!isLastSection && section.getSectionBreakTimeMinutes() > 0) {
                LocalTime sectionBreakEnd =
                        cursor.plusMinutes(section.getSectionBreakTimeMinutes());
                result.add(
                        new DraftTimelineEntryResponse(
                                i + 1, 0, "SECTION_BREAK", cursor, sectionBreakEnd, null));
                cursor = sectionBreakEnd;
            }
        }

        return List.copyOf(result);
    }

    /**
     * Maps a {@link DraftSection} to a {@link PhaseConfig} for the timeline engine.
     *
     * <p>Field mapping (AC-IMPL-DRAFT-CONFIG-TO-PHASE-CONFIG-MAPPING):
     *
     * <ul>
     *   <li>{@code lapCount} = {@code totalLaps} from the computed preview (field-aware formula per
     *       E48S10); for Siegerehrung phases {@code totalLaps == 0} → single zero-duration marker
     *       per TimelineCalculationService Javadoc line 44-46
     *   <li>{@code lapTimeMinutes} = {@code section.lapTimeMinutes}
     *   <li>{@code lapBreakMinutes} = {@code section.lapBreakTimeMinutes}
     *   <li>{@code phaseBreaks} = mapped from {@code section.breaks} (DraftBreak →
     *       PhaseBreakConfig)
     * </ul>
     *
     * <p>AC-IMPL-DEC-35-INTERFACE-FIRST inspection result: this mapping logic stays as a private
     * method inside {@code DefaultDraftService}. No separate mapper class is introduced; the
     * mapping is self-contained and does not cross module boundaries.
     *
     * @param section the draft section to map
     * @param totalLaps the precomputed total laps for this section (0 for Siegerehrung)
     * @param phaseNumber 1-based phase number within the draft
     * @return a {@link PhaseConfig} suitable for {@link TimelineCalculationService#calculate}
     */
    private static PhaseConfig toPhaseConfig(DraftSection section, int totalLaps, int phaseNumber) {
        List<PhaseBreakConfig> breaks =
                section.getBreaks().stream()
                        .map(
                                b ->
                                        new PhaseBreakConfig(
                                                b.getAfterLapNumber(),
                                                b.getDurationMinutes(),
                                                b.getLabel()))
                        .toList();
        // PhaseConfig requires lapTimeMinutes > 0 when lapCount > 0; for lapCount=0 (Siegerehrung),
        // lapTimeMinutes is ignored by the engine but must pass the constructor validation.
        // Use section.getLapTimeMinutes() which is always > 0 per DraftSection validation.
        return new PhaseConfig(
                phaseNumber,
                totalLaps,
                section.getLapTimeMinutes(),
                section.getLapBreakTimeMinutes(),
                breaks);
    }

    /**
     * Converts a {@link TimelineEntry} domain record to the REST response DTO {@link
     * DraftTimelineEntryResponse}.
     *
     * @param entry the domain timeline entry; must not be {@code null}
     * @return the response DTO
     */
    private static DraftTimelineEntryResponse toResponse(TimelineEntry entry) {
        return new DraftTimelineEntryResponse(
                entry.phaseNumber(),
                entry.lapNumber(),
                entry.type().name(),
                entry.startTime(),
                entry.endTime(),
                entry.label());
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
                            phaseId,
                            draftBreak.getAfterLapNumber(),
                            draftBreak.getDurationMinutes(),
                            draftBreak.getLabel());
            phaseBreakRepository.save(phaseBreak);
        }
    }
}
