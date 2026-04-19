package de.vvwt.tm.infrastructure.tournament;

import de.vvwt.tm.domain.ActivityTypeService;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST controller for activity type CRUD operations (E20S02, AC2 — RED skeleton).
 *
 * <p>Skeleton class committed RED before handler methods are implemented. Tests targeting this
 * class will receive HTTP 404 responses, satisfying AC1b behavioural-RED requirement.
 */
@RestController
@RequestMapping("/api/tournaments/{tournamentId}/activity-types")
public class ActivityTypeController {

    private final ActivityTypeService activityTypeService;

    public ActivityTypeController(ActivityTypeService activityTypeService) {
        this.activityTypeService = activityTypeService;
    }

    // Handler methods intentionally absent — RED state for AC1 anti-spoof
}
