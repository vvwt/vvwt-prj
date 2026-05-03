/**
 * Activity sub-module of the {@code tournament} bounded context (E45S01, DEC-21, DEC-35).
 *
 * <p>This package is the canonical home for activity-related public types relocated from {@code
 * de.vvwt.tm.domain.*} at E45S01: {@link de.vvwt.tm.tournament.activity.ActivityType}, {@link
 * de.vvwt.tm.tournament.activity.ActivityTypeService}, {@link
 * de.vvwt.tm.tournament.activity.ActivityTypeRepository}, {@link
 * de.vvwt.tm.tournament.activity.ActivityAssignmentService}, {@link
 * de.vvwt.tm.tournament.activity.ActivityAssignment}, {@link
 * de.vvwt.tm.tournament.activity.ActivityAssignmentResult}, {@link
 * de.vvwt.tm.tournament.activity.AssignmentRule}, and {@link
 * de.vvwt.tm.tournament.activity.UnsupportedAssignmentRuleException}.
 *
 * <p>This package is exposed as a named interface ({@code "activity"}) of the {@code tournament}
 * module, allowing other modules ({@code print}, {@code web}) to consume activity types without
 * violating Spring Modulith boundary rules (DEC-21). Consumers declare {@code allowedDependencies =
 * {"tournament", "tournament::activity", ...}} in their {@code @ApplicationModule} annotation.
 *
 * <p>Internal implementations ({@code DefaultActivityTypeService}, {@code
 * DefaultActivityTypeRepository}, {@code DefaultActivityAssignmentService}, {@code
 * FirstFreeRoundAssigner}, {@code LapSchedule}, {@code ActivityTypeCrudRepository}) live in {@code
 * de.vvwt.tm.tournament.activity.internal} and are NOT part of the named interface.
 *
 * @see de.vvwt.tm.tournament.exceptions package-info — same named-interface pattern
 * @since E45S01
 */
@org.springframework.modulith.NamedInterface("activity")
package de.vvwt.tm.tournament.activity;
