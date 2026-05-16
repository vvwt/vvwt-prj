// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
package de.vvwt.info.persistence.tenant;

import java.util.Optional;
import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.repository.CrudRepository;

/**
 * Spring Data JDBC repository for {@link TenantRecord}.
 *
 * <p>Provides CRUD access to the {@code tenant} table. Uses first-key-wins semantics per DEC-42 D3:
 * the first asymmetric-key submission for a given tenant-id permanently binds it. The DAO surface
 * is intentionally minimal; business rules (first-key-wins enforcement, max-tenant limit) live in
 * the service layer (E38S04 scope).
 *
 * @see <a href="../../../../../../../docs/governance/stories/E38S03.story.md">E38S03</a>
 * @see <a href="../../../../../../../docs/governance/decisions/DEC-42.md">DEC-42</a>
 */
public interface TenantDao extends CrudRepository<TenantRecord, String> {

    /**
     * Returns the default tenant for this deployment, if one has been registered.
     *
     * <p>DEC-42 D3: the default tenant is the implicit organizer that self-host deployments
     * register on first use. At most one default tenant exists per deployment.
     *
     * @return the default tenant record, or empty if no default tenant is registered yet
     */
    @Query("SELECT * FROM tenant WHERE is_default = TRUE LIMIT 1")
    Optional<TenantRecord> findDefaultTenant();
}
