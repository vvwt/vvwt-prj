package de.vvwt.tm.infoportal;

import de.vvwt.info.dto.registration.AlgorithmWarning;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * TM-side publisher integration service for the Public Participant Info Portal (AC1, AC2, AC3, AC9,
 * AC10, AC11, AC12, AC13).
 *
 * <p>Activated when {@code info-portal.url} is configured. Coordinates:
 *
 * <ul>
 *   <li>Tenant registration (POST /api/v1/register) — UNSIGNED per D-X4(b).
 *   <li>Tournament registration (POST /api/v1/tournaments/{t}/{l}/{id}/register) — SIGNED.
 *   <li>Delta publish (POST /api/v1/publish/{t}/{l}/{id}?seq=N) — SIGNED with JCS body.
 *   <li>Snapshot recovery on 409 FULL_RESYNC — posts snapshot to
 *       /api/v1/publish/{t}/{l}/{id}/snapshot.
 *   <li>Algorithm-deprecation-warning handling per DEC-43 D3.
 *   <li>Error-handling per AC11 (4xx/5xx branches).
 * </ul>
 *
 * <p>NO outbound queue (D-X8 c): events during 5xx outages are accepted-as-lost. The next event's
 * seq mismatch triggers 409 FULL_RESYNC → snapshot recovery.
 *
 * <p>DEC-58 Clause A + DEC-72 Clause A-ext: every self-created Spring component — including
 * {@code @Bean}-factory-produced first-party service beans — must have a public interface in the
 * bounded-context root package.
 *
 * @see de.vvwt.tm.infoportal.internal.DefaultInfoPortalPublisherService
 * @see <a
 *     href="../../../../../../../../.gaai/project/contexts/artefacts/stories/E38S09.story.md">E38S09</a>
 * @since E57S05 (DEC-58/DEC-72 interface extraction)
 */
public interface InfoPortalPublisherService {

    /**
     * Registers this TM instance with the info-server (UNSIGNED per D-X4 b — operator trust,
     * first-key-wins binding per DEC-42 D3).
     *
     * <p>On success, stores the public key binding. If {@code algorithm_warning} is present in the
     * response, invokes algorithm warning handling.
     */
    void registerTenant();

    /**
     * Registers a tournament via the dedicated endpoint (D-X2 c). Returns the {@code
     * tournament_token} + {@code per_tournament_secret} and persists them in {@code
     * info_portal_state}.
     *
     * @param tournamentId the tournament identifier
     * @param teamUuids UUIDs of participating teams (may be empty for pre-registration)
     */
    void registerTournament(String tournamentId, List<UUID> teamUuids);

    /**
     * Publishes a domain event delta to the info-server (AC2, AC9, AC11, AC12).
     *
     * <p>Seq is assigned atomically before posting. On 409 FULL_RESYNC, the current {@code
     * last_published_seq} is retained (NOT reset to 1) and a snapshot is posted (AC3, AC12).
     *
     * @param tournamentId tournament identifier
     * @param eventJson JSON string of the {@code DomainEvent} payload
     */
    void publishDelta(String tournamentId, String eventJson);

    /**
     * Posts the current tournament snapshot to recover from 409 FULL_RESYNC (AC3).
     *
     * <p>Seq is NOT reset. The snapshot carries the current {@code last_published_seq} value.
     *
     * @param tournamentId tournament identifier
     * @param snapshotJson JSON string of the current tournament state snapshot
     */
    void postSnapshot(String tournamentId, String snapshotJson);

    /**
     * Handles an {@link AlgorithmWarning} from a registration response (AC10, DEC-43 D3).
     *
     * @param warning the algorithm warning, or {@code null} if none
     */
    void handleAlgorithmWarning(AlgorithmWarning warning);

    /**
     * Returns the current publisher status (for admin UI / {@link InfoPortalStatusController}).
     *
     * @return the mutable {@link PublisherStatus} holder
     */
    PublisherStatus getStatus();

    // -------------------------------------------------------------------------
    // PublisherStatus nested class (moved here from DefaultInfoPortalPublisherService so that
    // InfoPortalStatusController can import InfoPortalPublisherService.PublisherStatus without
    // referencing the .internal package — DEC-35, DEC-58 Clause A)
    // -------------------------------------------------------------------------

    /**
     * Mutable status holder for the publisher (admin UI data source via {@link
     * InfoPortalStatusController}).
     */
    class PublisherStatus {

        /** Deprecation severity communicated by the info-server algorithm-warning response. */
        public enum DeprecationSeverity {
            HIGH,
            LOW
        }

        private boolean registered;
        private boolean registrationError;
        private boolean signatureMismatch;
        private boolean algorithmDeprecatedHardStop;
        private int consecutiveServerErrors;

        @SuppressWarnings("unused")
        private int rateLimitedCount;

        private String deprecationAlgorithmId;
        private LocalDate deprecationDate;
        private DeprecationSeverity deprecationSeverity;
        private boolean publisherUnhealthy;

        public boolean isRegistered() {
            return registered;
        }

        public void setRegistered(boolean v) {
            this.registered = v;
        }

        public boolean isRegistrationError() {
            return registrationError;
        }

        public void setRegistrationError(boolean v) {
            this.registrationError = v;
        }

        public boolean isSignatureMismatch() {
            return signatureMismatch;
        }

        public void setSignatureMismatch(boolean v) {
            this.signatureMismatch = v;
        }

        public boolean isAlgorithmDeprecatedHardStop() {
            return algorithmDeprecatedHardStop;
        }

        public void setAlgorithmDeprecatedHardStop(boolean v) {
            this.algorithmDeprecatedHardStop = v;
        }

        public boolean hasDeprecationWarning() {
            return deprecationAlgorithmId != null;
        }

        public DeprecationSeverity getDeprecationSeverity() {
            return deprecationSeverity;
        }

        public String getDeprecationAlgorithmId() {
            return deprecationAlgorithmId;
        }

        public LocalDate getDeprecationDate() {
            return deprecationDate;
        }

        public boolean isPublisherUnhealthy() {
            return publisherUnhealthy;
        }

        public void setDeprecationWarning(
                String algorithmId, LocalDate date, DeprecationSeverity severity) {
            this.deprecationAlgorithmId = algorithmId;
            this.deprecationDate = date;
            this.deprecationSeverity = severity;
        }

        public void recordPublishSuccess() {
            consecutiveServerErrors = 0;
            publisherUnhealthy = false;
        }

        public void recordServerError() {
            consecutiveServerErrors++;
            // AC11: after 5 consecutive failures in 5-minute window → admin-UI indicator
            if (consecutiveServerErrors >= 5) {
                publisherUnhealthy = true;
            }
        }

        public void recordRateLimited() {
            rateLimitedCount++;
        }
    }
}
