package de.vvwt.tm.infoportal;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;
import de.vvwt.tm.infoportal.InfoPortalPublisherService.PublisherStatus;
import de.vvwt.tm.infoportal.InfoPortalPublisherService.PublisherStatus.DeprecationSeverity;
import java.time.LocalDate;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST controller exposing the TM publisher status for the admin Svelte UI (AC4, AC10).
 *
 * <p>Exposes {@code GET /api/info-portal/status} returning a JSON view of {@link PublisherStatus}.
 * Used by the {@code InfoPortalStatus.svelte} component to surface algorithm-deprecation warnings
 * and error states in the admin UI.
 *
 * <p>Activated only when {@code info-portal.url} is configured (registered by {@link
 * InfoPortalConfig#infoPortalStatusController} which is
 * {@code @ConditionalOnProperty(prefix="info-portal", name="url")}). Not component-scanned directly
 * — must be registered via the config factory method.
 *
 * @see <a
 *     href="../../../../../../../../.gaai/project/contexts/artefacts/stories/E38S09.story.md">E38S09
 *     AC4, AC10</a>
 * @see InfoPortalConfig
 */
@RestController
@RequestMapping("/api/info-portal")
@ConditionalOnProperty(prefix = "info-portal", name = "url")
public class InfoPortalStatusController {

    private final InfoPortalPublisherService publisherService;

    public InfoPortalStatusController(InfoPortalPublisherService publisherService) {
        this.publisherService = publisherService;
    }

    /**
     * Returns the current publisher status snapshot.
     *
     * <p>The response includes error flags and an optional {@code deprecationWarning} object
     * (omitted when no warning is active — per {@code @JsonInclude(NON_NULL)}).
     *
     * @return 200 with {@link StatusResponse} JSON
     */
    @GetMapping("/status")
    public ResponseEntity<StatusResponse> getStatus() {
        PublisherStatus s = publisherService.getStatus();
        DeprecationWarningDto warningDto = null;
        if (s.hasDeprecationWarning()) {
            warningDto =
                    new DeprecationWarningDto(
                            s.getDeprecationAlgorithmId(),
                            s.getDeprecationDate(),
                            s.getDeprecationSeverity());
        }
        return ResponseEntity.ok(
                new StatusResponse(
                        s.isRegistered(),
                        s.isRegistrationError(),
                        s.isSignatureMismatch(),
                        s.isAlgorithmDeprecatedHardStop(),
                        s.isPublisherUnhealthy(),
                        warningDto));
    }

    // -------------------------------------------------------------------------
    // Response DTOs (package-private for testability, immutable records)
    // -------------------------------------------------------------------------

    /**
     * Top-level status response. {@code deprecationWarning} is omitted from JSON when null
     * ({@code @JsonInclude(NON_NULL)}).
     */
    @JsonInclude(Include.NON_NULL)
    record StatusResponse(
            boolean registered,
            boolean registrationError,
            boolean signatureMismatch,
            boolean algorithmDeprecatedHardStop,
            boolean publisherUnhealthy,
            DeprecationWarningDto deprecationWarning) {}

    /**
     * Deprecation warning DTO included in status when an algorithm warning is active (AC4, AC10).
     */
    record DeprecationWarningDto(
            String algorithmId, LocalDate deprecationDate, DeprecationSeverity severity) {}
}
