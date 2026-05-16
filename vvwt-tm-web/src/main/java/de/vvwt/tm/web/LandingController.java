// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
package de.vvwt.tm.web;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * Convenience redirect: maps exact path {@code GET /} to {@code 302 Found Location: /admin/}.
 *
 * <p>This controller's sole responsibility is to forward users who type the bare root URL ({@code
 * http://<host>:8080/}) into a browser address bar to the Administration UI entry point without
 * requiring knowledge of the {@code /admin/} path convention.
 *
 * <h2>HTTP 302 vs. 301</h2>
 *
 * <p>HTTP {@code 302 Found} (temporary redirect) is used deliberately per Discovery brief T-1:
 * preserves the option to evolve {@code /} into a real informational landing page in a future Epic
 * without browser-cache lock-in that a {@code 301 Permanent} would cause. The marginal extra HTTP
 * roundtrip per visit is negligible on LAN-mode (DEC-16) at admin-login frequency.
 *
 * <h2>Exact-path only — no catch-all (AC3)</h2>
 *
 * <p>The {@code @GetMapping("/")} annotation matches only the exact path {@code /}. Unknown paths
 * (e.g., {@code /foo}, {@code /anything-else}) continue to produce {@code HTTP 404} via Spring's
 * default No-Handler behaviour. A catch-all is explicitly forbidden by AC3.
 *
 * <h2>Security (AC7 + AC8)</h2>
 *
 * <p>Access to {@code /} is declared {@code permitAll()} in {@link
 * de.vvwt.tm.auth.internal.SecurityConfig} — the {@code requestMatchers("/").permitAll()} rule is
 * placed BEFORE the {@code anyRequest().authenticated()} catch-all so that unauthenticated requests
 * reach this controller (AC8: unauthenticated {@code GET /} returns {@code 302}, NOT {@code 401}).
 *
 * <h2>Single-responsibility (AC6)</h2>
 *
 * <p>This redirect is intentionally placed in a dedicated {@code LandingController} and MUST NOT be
 * added as an additional {@code @GetMapping} on {@link AdminSpaController}. {@code
 * AdminSpaController} serves SPA shells; root-path redirection is a distinct concern per Discovery
 * brief T-5.
 *
 * <h2>Module placement (AC5 + DEC-40 Clause A)</h2>
 *
 * <p>Resides in {@code de.vvwt.tm.web} (NOT in any bounded-context module; NOT in {@code
 * de.vvwt.tm.web.internal}). This controller depends on no bounded-context module — no expansion of
 * {@code web.allowedDependencies} is required.
 *
 * @see de.vvwt.tm.auth.internal.SecurityConfig
 * @see AdminSpaController
 * @since E43S01
 */
@Controller
public class LandingController {

    /**
     * Handles {@code GET /} and returns a {@code 302 Found} redirect to {@code /admin/}.
     *
     * <p>Uses Spring's {@code redirect:} view-name prefix, which produces a {@code
     * org.springframework.web.servlet.view.RedirectView} with HTTP status {@code 302 Found} and
     * {@code Location: /admin/} (trailing slash included per AC1).
     *
     * @return a Spring redirect view name resolving to {@code HTTP 302 Location: /admin/}
     */
    @GetMapping("/")
    public String redirectToAdmin() {
        return "redirect:/admin/";
    }
}
