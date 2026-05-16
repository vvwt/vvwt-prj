// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
package de.vvwt.info.registration.config;

import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration-properties binding for registration-related settings (AC10, DEC-42 D3).
 *
 * <p>Bound to the {@code vvwt.info} prefix. Profile-specific YAML sets:
 *
 * <ul>
 *   <li>Self-host: {@code registration.mode=OPEN_FCFS}, {@code tenant.max-tenants=1}, {@code
 *       default-tenant.mode=CONFIG_OR_FCFS}
 *   <li>Primary: {@code registration.mode=INVITATION_ONLY}, {@code tenant.max-tenants=-1},
 *       invitation tokens listed under {@code invitation-tokens}
 * </ul>
 *
 * @see <a href="../../../../../../../../docs/governance/stories/E38S04.story.md">E38S04 AC9,
 *     AC10</a>
 * @see <a href="../../../../../../../../docs/governance/decisions/DEC-42.md">DEC-42 D3</a>
 */
@ConfigurationProperties(prefix = "vvwt.info")
public class RegistrationProperties {

    private Registration registration = new Registration();
    private Tenant tenant = new Tenant();
    private DefaultTenant defaultTenant = new DefaultTenant();
    private List<String> invitationTokens = new ArrayList<>();

    public Registration getRegistration() {
        return registration;
    }

    public void setRegistration(Registration registration) {
        this.registration = registration;
    }

    public Tenant getTenant() {
        return tenant;
    }

    public void setTenant(Tenant tenant) {
        this.tenant = tenant;
    }

    public DefaultTenant getDefaultTenant() {
        return defaultTenant;
    }

    public void setDefaultTenant(DefaultTenant defaultTenant) {
        this.defaultTenant = defaultTenant;
    }

    public List<String> getInvitationTokens() {
        return invitationTokens;
    }

    public void setInvitationTokens(List<String> invitationTokens) {
        this.invitationTokens = invitationTokens;
    }

    /** Registration mode settings. */
    public static class Registration {
        private String mode = "OPEN_FCFS";

        public String getMode() {
            return mode;
        }

        public void setMode(String mode) {
            this.mode = mode;
        }
    }

    /** Tenant limit settings (DEC-42 D3). */
    public static class Tenant {
        /**
         * Maximum number of registered tenants. Default is 1 (self-host single-tenant-only per
         * DEC-42 D3). Set to -1 for unlimited (primary profile).
         */
        private int maxTenants = 1;

        /** Optional pre-configured default tenant ID (CONFIG path of CONFIG_OR_FCFS). */
        private String defaultTenantId;

        public int getMaxTenants() {
            return maxTenants;
        }

        public void setMaxTenants(int maxTenants) {
            this.maxTenants = maxTenants;
        }

        public String getDefaultTenantId() {
            return defaultTenantId;
        }

        public void setDefaultTenantId(String defaultTenantId) {
            this.defaultTenantId = defaultTenantId;
        }
    }

    /** Default-tenant mode settings (DEC-42 D3). */
    public static class DefaultTenant {
        /**
         * Mode for the default-tenant slot: CONFIG_OR_FCFS (self-host) or CONFIG_ONLY (primary).
         */
        private String mode = "CONFIG_OR_FCFS";

        public String getMode() {
            return mode;
        }

        public void setMode(String mode) {
            this.mode = mode;
        }
    }
}
