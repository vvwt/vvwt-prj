package de.vvwt.tm.infrastructure.tournament;

import de.vvwt.tm.domain.ActivityTypeService;
import de.vvwt.tm.domain.activity.ActivityAssignmentService;
import de.vvwt.tm.domain.repo.MatchRepository;
import de.vvwt.tm.domain.repo.PhaseRepository;
import de.vvwt.tm.domain.repo.TeamAvatarRepository;
import de.vvwt.tm.domain.repo.TeamRepository;
import de.vvwt.tm.domain.repo.TournamentRepository;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST controller for the activity assignment preview (E20S02, AC2 — RED skeleton).
 *
 * <p>Skeleton class committed RED before handler methods are implemented. Tests targeting this
 * class will receive HTTP 404 responses, satisfying AC1b behavioural-RED requirement.
 */
@RestController
@RequestMapping("/api/tournaments/{tournamentId}/activity-assignments")
public class ActivityAssignmentPreviewController {

    private final TournamentRepository tournamentRepository;
    private final PhaseRepository phaseRepository;
    private final MatchRepository matchRepository;
    private final TeamAvatarRepository teamAvatarRepository;
    private final TeamRepository teamRepository;
    private final ActivityTypeService activityTypeService;
    private final ActivityAssignmentService activityAssignmentService;

    public ActivityAssignmentPreviewController(
            TournamentRepository tournamentRepository,
            PhaseRepository phaseRepository,
            MatchRepository matchRepository,
            TeamAvatarRepository teamAvatarRepository,
            TeamRepository teamRepository,
            ActivityTypeService activityTypeService,
            ActivityAssignmentService activityAssignmentService) {
        this.tournamentRepository = tournamentRepository;
        this.phaseRepository = phaseRepository;
        this.matchRepository = matchRepository;
        this.teamAvatarRepository = teamAvatarRepository;
        this.teamRepository = teamRepository;
        this.activityTypeService = activityTypeService;
        this.activityAssignmentService = activityAssignmentService;
    }

    // Handler methods intentionally absent — RED state for AC1 anti-spoof
}
