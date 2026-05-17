// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
package de.vvwt.tm.tenant;

import org.springframework.boot.ApplicationRunner;

/**
 * Interface for the tenant bootstrap runner that initializes the default tenant on first start.
 *
 * <p>DEC-58 Clause A + DEC-72 Clause A-ext: every self-created Spring component — including
 * {@code @Bean}-factory-produced first-party service beans — must have a public interface in the
 * bounded-context root package.
 *
 * <p>Extends {@link ApplicationRunner} so that Spring Boot's startup mechanism invokes {@link
 * #run(org.springframework.boot.ApplicationArguments)} at context refresh.
 *
 * @see de.vvwt.tm.tenant.internal.DefaultTenantBootstrapRunner
 * @see <a href="../../../../../../../../docs/governance/stories/E14S05.story.md">Story E14S05</a>
 * @see <a href="../../../../../../../../docs/governance/decisions/DEC-20.md">DEC-20</a>
 * @see <a href="../../../../../../../../docs/governance/decisions/DEC-21.md">DEC-21</a>
 * @since E57S05 (DEC-58/DEC-72 interface extraction)
 */
public interface TenantBootstrapRunner extends ApplicationRunner {}
