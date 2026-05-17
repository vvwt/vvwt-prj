// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
package de.vvwt.tm.web.slotopt;

import de.vvwt.tm.slotopt.DispatcherReachabilityService;
import de.vvwt.tm.slotopt.DispatcherStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST controller for the dispatcher reachability status and manual re-check (E63S07,
 * AC-TEST-STATUS-DISPLAYED, AC-TEST-MANUAL-RECHECK, DEC-40 Clause A, DEC-49 D-12).
 *
 * <h2>Endpoints</h2>
 *
 * <ul>
 *   <li>{@code GET /api/slotopt/dispatcher/status} — returns the current dispatcher reachability
 *       status ({@link DispatcherStatus#REACHABLE} / {@link DispatcherStatus#UNREACHABLE} / {@link
 *       DispatcherStatus#NOT_CONFIGURED}) by probing the dispatcher via {@link
 *       DispatcherReachabilityService#getStatus()}.
 *   <li>{@code POST /api/slotopt/dispatcher/recheck} — explicit operator-initiated re-probe;
 *       functionally identical to GET /status but semantically signals an operator action.
 * </ul>
 *
 * <h2>Placement (DEC-40 Clause A)</h2>
 *
 * <p>Placed in {@code de.vvwt.tm.web.slotopt} sub-package of the {@code web} Modulith module,
 * consistent with {@link SlotOptimizationCancelController}. No {@code @ApplicationModule}
 * declaration needed — sub-packages inherit from the parent's declaration.
 *
 * <h2>Interface mandate (DEC-58 Clause D)</h2>
 *
 * <p>{@code @RestController} beans are excluded from the DEC-58/DEC-72 interface mandate per Clause
 * D (primary adapters; no interface is required for REST controllers).
 *
 * <h2>Authentication (AC-SEC-RECHECK-ENDPOINT-AUTHENTICATED)</h2>
 *
 * <p>Both endpoints are protected by the production Spring Security filter chain that covers all
 * {@code /api/**} paths with HTTP Basic auth (same admin authentication as the rest of the TM admin
 * console). No anonymous access is possible.
 *
 * <h2>Concurrency (AC-ERR-RECHECK-CONCURRENT-CLICKS)</h2>
 *
 * <p>This controller is stateless. Each request invokes an independent HEAD probe bounded by {@code
 * tm.slotopt.dispatcher.reachability-timeout-ms}. Concurrent re-check clicks produce concurrent
 * probes, each completing independently within the timeout. No pile-up or destabilization occurs.
 * The UI debounces by disabling the re-check button while a request is in-flight.
 *
 * <h2>NOT_CONFIGURED is not an error (AC-ERR-NOT-CONFIGURED-IS-NOT-AN-ERROR)</h2>
 *
 * <p>When {@code tm.slotopt.dispatcher.url} is null/unset, both endpoints return HTTP 200 with
 * {@link DispatcherStatus#NOT_CONFIGURED} — not a 5xx error response.
 *
 * <h2>Response shape (AC-SEC-STATUS-NO-INTERNAL-LEAK)</h2>
 *
 * <p>The response exposes only the {@link DispatcherStatus} and, when configured, the dispatcher
 * URL already visible to the operator. No credentials, tokens, stack traces, or internal error
 * details are included.
 *
 * @see DispatcherReachabilityService
 * @see DispatcherStatus
 * @see SlotOptimizationCancelController
 * @see <a href="../../../../../../../../../docs/governance/decisions/DEC-40.md">DEC-40 Clause A</a>
 * @see <a href="../../../../../../../../../docs/governance/decisions/DEC-49.md">DEC-49 D-12</a>
 * @see <a href="../../../../../../../../../docs/governance/stories/E63S07.story.md">Story
 *     E63S07</a>
 */
@RestController
@RequestMapping("/api/slotopt/dispatcher")
public class DispatcherStatusController {

    private static final Logger LOG = LoggerFactory.getLogger(DispatcherStatusController.class);

    private final DispatcherReachabilityService reachabilityService;
    private final String dispatcherUrl;

    /**
     * Constructs the controller.
     *
     * @param reachabilityService the service that probes the dispatcher URL
     * @param dispatcherUrl the configured dispatcher URL (may be null or blank when NOT_CONFIGURED)
     */
    public DispatcherStatusController(
            DispatcherReachabilityService reachabilityService,
            @org.springframework.beans.factory.annotation.Value("${tm.slotopt.dispatcher.url:}")
                    String dispatcherUrl) {
        this.reachabilityService = reachabilityService;
        this.dispatcherUrl = dispatcherUrl.isBlank() ? null : dispatcherUrl;
    }

    /**
     * {@code GET /api/slotopt/dispatcher/status}
     *
     * <p>Probes the dispatcher reachability and returns the current tri-state status.
     *
     * @return HTTP 200 with {@link DispatcherStatusResponse}
     */
    @GetMapping("/status")
    public ResponseEntity<DispatcherStatusResponse> getStatus() {
        DispatcherStatus status = reachabilityService.getStatus();
        LOG.debug("DispatcherStatusController.getStatus: status={}", status);
        return ResponseEntity.ok(buildResponse(status));
    }

    /**
     * {@code POST /api/slotopt/dispatcher/recheck}
     *
     * <p>Explicit operator-initiated re-probe. Functionally identical to GET /status — triggers a
     * fresh HEAD probe and returns the result. Distinct URL for semantic clarity (operator action
     * vs. passive read).
     *
     * <p>AC-ERR-RECHECK-CONCURRENT-CLICKS: each invocation is independent and bounded by the
     * configured reachability timeout. Concurrent requests do not interfere.
     *
     * @return HTTP 200 with {@link DispatcherStatusResponse}
     */
    @PostMapping("/recheck")
    public ResponseEntity<DispatcherStatusResponse> recheck() {
        DispatcherStatus status = reachabilityService.getStatus();
        LOG.debug("DispatcherStatusController.recheck: status={}", status);
        return ResponseEntity.ok(buildResponse(status));
    }

    private DispatcherStatusResponse buildResponse(DispatcherStatus status) {
        // Expose dispatcher URL only when configured (AC-SEC-STATUS-NO-INTERNAL-LEAK:
        // URL is already known to the operator from config; safe to surface in the UI).
        // When NOT_CONFIGURED, dispatcherUrl is null — suppressed by @JsonInclude(NON_NULL).
        return new DispatcherStatusResponse(status, dispatcherUrl);
    }

    // -------------------------------------------------------------------------
    // Response DTO
    // -------------------------------------------------------------------------

    /**
     * HTTP response body for both dispatcher status endpoints.
     *
     * <p>Fields:
     *
     * <ul>
     *   <li>{@link #status} — the tri-state reachability status
     *   <li>{@link #dispatcherUrl} — the configured URL, or {@code null} when NOT_CONFIGURED
     *       ({@code @JsonInclude(NON_NULL)} suppresses the field when null)
     * </ul>
     *
     * <p>AC-SEC-STATUS-NO-INTERNAL-LEAK: no credentials, stack details, or internal error
     * information are included.
     *
     * @param status the reachability status
     * @param dispatcherUrl the configured dispatcher base URL; null when not configured
     */
    @com.fasterxml.jackson.annotation.JsonInclude(
            com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
    public record DispatcherStatusResponse(DispatcherStatus status, String dispatcherUrl) {}
}
