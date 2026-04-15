package de.vvwt.tm.infrastructure.web.certificate;

import de.vvwt.tm.domain.Phase;
import de.vvwt.tm.domain.Team;
import de.vvwt.tm.domain.certificate.CertificateTemplateService;
import de.vvwt.tm.domain.photo.PhotoStorageService;
import de.vvwt.tm.domain.repo.TeamRepository;
import de.vvwt.tm.domain.repo.TournamentRepository;
import de.vvwt.tm.infrastructure.print.CertificateAssembler;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.UUID;

/**
 * REST controller for the certificate readiness check endpoint (E12S07 AC1).
 *
 * <h2>Endpoint</h2>
 * <pre>
 *   GET /api/tournaments/{tournamentId}/certificate-readiness
 * </pre>
 *
 * <p>Returns a single JSON object aggregating all readiness signals the UI needs for the
 * "Urkunden" readiness checklist — template status, standings status, and photo completeness.
 * This avoids multiple sequential API calls from the frontend.
 *
 * <h2>Authentication (AC8)</h2>
 * <p>Falls under {@code /api/**} which requires admin authentication per
 * {@link de.vvwt.tm.auth.SecurityConfig}. No additional security logic needed.
 *
 * <h2>Tenant scoping (DEC-5, AC8)</h2>
 * <p>Tournament existence is validated via the tenant-scoped {@link TournamentRepository}.
 * A missing or wrong-tenant tournament produces HTTP 404 via {@link NoSuchElementException}.
 *
 * @see CertificateReadinessResponse
 * @see CertificateAssembler#getFinalPhase(UUID)
 * @see CertificateAssembler#computePlacementOrder(UUID, Phase)
 */
@RestController
public class CertificateReadinessController {

    private final TournamentRepository tournamentRepository;
    private final CertificateTemplateService certificateTemplateService;
    private final CertificateAssembler certificateAssembler;
    private final TeamRepository teamRepository;
    private final PhotoStorageService photoStorageService;

    public CertificateReadinessController(
            TournamentRepository tournamentRepository,
            CertificateTemplateService certificateTemplateService,
            CertificateAssembler certificateAssembler,
            TeamRepository teamRepository,
            PhotoStorageService photoStorageService) {
        this.tournamentRepository = tournamentRepository;
        this.certificateTemplateService = certificateTemplateService;
        this.certificateAssembler = certificateAssembler;
        this.teamRepository = teamRepository;
        this.photoStorageService = photoStorageService;
    }

    /**
     * Returns the certificate readiness status for the given tournament (E12S07 AC1).
     *
     * <p>Response fields:
     * <ul>
     *   <li>{@code templateUploaded}   — whether a certificate template has been stored</li>
     *   <li>{@code standingsAvailable} — whether the final phase exists and has rated placements</li>
     *   <li>{@code totalTeams}         — total number of teams in the tournament</li>
     *   <li>{@code teamsWithPhoto}     — how many teams have a photo uploaded</li>
     * </ul>
     *
     * @param tournamentId tournament UUID (tenant-scoped)
     * @return 200 with {@link CertificateReadinessResponse}; 404 if tournament not found
     */
    @GetMapping("/api/tournaments/{tournamentId}/certificate-readiness")
    public ResponseEntity<CertificateReadinessResponse> getReadiness(
            @PathVariable("tournamentId") UUID tournamentId) {

        // AC8: tenant-scoped — throws NoSuchElementException → 404 for wrong/missing tournament
        tournamentRepository.findById(tournamentId)
                .orElseThrow(() -> new NoSuchElementException(
                        "Tournament not found: " + tournamentId));

        // Template check — AC1
        boolean templateUploaded = certificateTemplateService
                .retrieveMetadata(tournamentId)
                .isPresent();

        // Standings check — AC1
        boolean standingsAvailable = false;
        Optional<Phase> finalPhase = certificateAssembler.getFinalPhase(tournamentId);
        if (finalPhase.isPresent()) {
            List<CertificateAssembler.AvatarPlacement> placements =
                    certificateAssembler.computePlacementOrder(tournamentId, finalPhase.get());
            standingsAvailable = !placements.isEmpty();
        }

        // Photo counts — AC1
        List<Team> teams = teamRepository.findByTournamentId(tournamentId);
        int totalTeams = teams.size();
        int teamsWithPhoto = 0;
        for (Team team : teams) {
            if (photoStorageService.hasPhoto(tournamentId, team.getId())) {
                teamsWithPhoto++;
            }
        }

        return ResponseEntity.ok(new CertificateReadinessResponse(
                templateUploaded,
                standingsAvailable,
                totalTeams,
                teamsWithPhoto
        ));
    }
}
