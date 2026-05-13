package de.vvwt.tm.web;

import de.vvwt.tm.tournament.Match;
import de.vvwt.tm.tournament.MatchRepository;
import de.vvwt.tm.tournament.PhaseTransitionService;
import de.vvwt.tm.tournament.TeamAvatarProposal;
import de.vvwt.tm.web.internal.dto.MatchSummaryResponse;
import de.vvwt.tm.web.internal.dto.TeamAvatarAssignment;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST controller for Phase-Transition propose/commit operations (DEC-40, E48S07).
 *
 * <p>Primary-adapter-isolation: lives in {@code de.vvwt.tm.web} per DEC-40 Clause A. Delegates all
 * business logic to {@link PhaseTransitionService}.
 *
 * <h2>Endpoints</h2>
 *
 * <ul>
 *   <li>GET /api/phases/{phaseId}/transition-proposal — proposes initial assignment for Phase N+1
 *   <li>POST /api/phases/{phaseId}/transition-commit — commits (admin-corrected) assignment as
 *       TeamAvatars
 *   <li>GET /api/phases/{phaseId}/matches — returns match summary list for correction navigation
 *       (E48S25, AC-FE-PHASELIST-CORRECTION-LINKS)
 * </ul>
 *
 * <h2>Modulith cycle prevention (DEC-40 Clause B 2026-04-27)</h2>
 *
 * <p>The request body DTO {@link TeamAvatarAssignment} (in {@code web.internal.dto}) is mapped to
 * {@link TeamAvatarProposal} (in {@code tournament.*}) INSIDE the controller before calling the
 * service. This prevents a forbidden {@code tournament→web} import. The controller is the sole
 * mapping point.
 *
 * <h2>Security</h2>
 *
 * <p>Both endpoints require authenticated Admin per AC-SECURITY-TRANSITION-ENDPOINTS-AUTH. Secured
 * via global {@code SecurityFilterChain} (Spring Security HTTP Basic). Anonymous requests → 401.
 *
 * @see PhaseTransitionService
 * @see TeamAvatarProposal
 * @see TeamAvatarAssignment
 * @see <a href="DEC-40">DEC-40 — Primary-Adapter-Isolation</a>
 * @see <a href="DEC-44">DEC-44 — web-module ITs use @SpringBootTest(RANDOM_PORT)</a>
 * @see <a href="E48S07">E48S07 — Drag&amp;Drop Phase-Transition Backend</a>
 */
@RestController("tmPhaseTransitionController")
@RequestMapping("/api/phases")
public class PhaseTransitionController {

    private final PhaseTransitionService phaseTransitionService;
    private final MatchRepository matchRepository;

    public PhaseTransitionController(
            @Qualifier("tmPhaseTransitionService") PhaseTransitionService phaseTransitionService,
            MatchRepository matchRepository) {
        this.phaseTransitionService = phaseTransitionService;
        this.matchRepository = matchRepository;
    }

    // -------------------------------------------------------------------------
    // GET /api/phases/{phaseId}/transition-proposal
    // (AC-IMPL-CONTROLLER, AC-SECURITY-TRANSITION-ENDPOINTS-AUTH)
    // -------------------------------------------------------------------------

    /**
     * Proposes an initial team-to-(group, position) distribution for the target phase.
     *
     * <p>Pure read-only — no lock, no persistence. Uses the phase's {@code sortType} from {@code
     * draft_json} to compute the proposal.
     *
     * @param phaseId the UUID of the target (next) phase
     * @return 200 OK with the list of proposed assignments; 400 if draft_json is null or invalid;
     *     404 if the phase does not exist
     */
    @GetMapping("/{phaseId}/transition-proposal")
    public ResponseEntity<List<TeamAvatarProposal>> proposeTransition(
            @PathVariable("phaseId") UUID phaseId) {
        List<TeamAvatarProposal> proposals = phaseTransitionService.proposeTransition(phaseId);
        return ResponseEntity.ok(proposals);
    }

    // -------------------------------------------------------------------------
    // POST /api/phases/{phaseId}/transition-commit
    // (AC-IMPL-CONTROLLER, AC-SECURITY-TRANSITION-ENDPOINTS-AUTH)
    // -------------------------------------------------------------------------

    /**
     * Commits a (possibly admin-corrected) team assignment for the target phase.
     *
     * <p>Acquires per-tournament pessimistic DB row-lock (DEC-37 Clause B). Persists TeamAvatars
     * and triggers match generation.
     *
     * <p>Maps {@link TeamAvatarAssignment} (web-tier DTO) → {@link TeamAvatarProposal} (domain
     * type) before calling the service to prevent a forbidden {@code tournament→web} import per
     * DEC-40 Clause B.
     *
     * @param phaseId the UUID of the target phase
     * @param assignments the final (admin-corrected) team-to-(group, position) assignments
     * @return 200 OK on success; 400 if assignments are invalid or draft_json is null
     */
    @PostMapping("/{phaseId}/transition-commit")
    public ResponseEntity<Void> commitTransition(
            @PathVariable("phaseId") UUID phaseId,
            @RequestBody List<TeamAvatarAssignment> assignments) {
        // Map web-tier DTO → domain type (Modulith cycle prevention per DEC-40 Clause B).
        // Commit path only needs structural identity fields (teamId, groupNumber, groupPosition) —
        // display fields (teamNumber, teamDescription, sourceGroupNumber, sourceGroupPosition) are
        // not used by commitTransition. Use forCommit factory to create minimal proposals.
        // (E48S20 AC-IMPL-CONTROLLER-PASSTHROUGH)
        List<TeamAvatarProposal> domainAssignments =
                assignments.stream()
                        .map(
                                a ->
                                        TeamAvatarProposal.forCommit(
                                                a.teamId(), a.groupNumber(), a.groupPosition()))
                        .collect(Collectors.toList());
        phaseTransitionService.commitTransition(phaseId, domainAssignments);
        return ResponseEntity.ok().build();
    }

    // -------------------------------------------------------------------------
    // GET /api/phases/{phaseId}/matches
    // (E48S25, AC-FE-PHASELIST-CORRECTION-LINKS — match list for correction navigation)
    // -------------------------------------------------------------------------

    /**
     * Returns a summary list of all matches in the given phase for correction-route navigation.
     *
     * <p>The Admin SPA uses this endpoint to render "Korrigieren" links per match row in the Phase
     * view (PhaseList.svelte). Each match summary includes the match state so the SPA can suppress
     * correction links for INPROGRESS and ONCHECK matches (which the backend guards too).
     *
     * <p>Read-only — no lock, no persistence. Tenant-scoped via {@link MatchRepository}.
     *
     * @param phaseId the UUID of the phase whose matches to list
     * @return 200 OK with the list of match summaries (empty list if no matches exist)
     * @see de.vvwt.tm.web.internal.dto.MatchSummaryResponse
     * @see <a href="E48S25">E48S25 — Operator Match Score Correction + Nacherfassung</a>
     */
    @GetMapping("/{phaseId}/matches")
    public ResponseEntity<List<MatchSummaryResponse>> listPhaseMatches(
            @PathVariable("phaseId") UUID phaseId) {
        List<Match> matches = matchRepository.findByPhaseId(phaseId);
        List<MatchSummaryResponse> response =
                matches.stream()
                        .map(
                                m ->
                                        new MatchSummaryResponse(
                                                m.getId(),
                                                m.getMatchState().name(),
                                                m.getLapNumber(),
                                                m.getFieldNumber()))
                        .toList();
        return ResponseEntity.ok(response);
    }
}
