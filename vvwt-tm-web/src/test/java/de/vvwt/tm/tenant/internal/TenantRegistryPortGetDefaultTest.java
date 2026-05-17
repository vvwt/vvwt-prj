// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
package de.vvwt.tm.tenant.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.vvwt.tm.tenant.TenantRegistryPort;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * TDD Red-Green tests for {@link TenantRegistryPort#getDefault()} — AC1 (DEC-22 Iron Law).
 *
 * <p>This test file was written BEFORE {@code getDefault()} was added to the interface, proving the
 * RED state (compilation failure) as mandated by DEC-22.
 *
 * <p>Tests use an in-memory stub that extends {@link StubTenantRegistryPort} to implement the new
 * method, proving behaviour without coupling to production {@link TenantFileRegistry}.
 *
 * <p>Story: E14S12 — DEC-22/DEC-24/DEC-20/DEC-21.
 */
class TenantRegistryPortGetDefaultTest {

    // -------------------------------------------------------------------------
    // Test double — extends map-based stub with getDefault() implementation
    // -------------------------------------------------------------------------

    /**
     * In-memory stub that implements {@link TenantRegistryPort} including the new {@code
     * getDefault()} method. Stores tenants in a list. The last registered tenant is not designated
     * "default" unless it has displayName "Default (LAN)".
     */
    static class StubTenantRegistryPort implements TenantRegistryPort {

        static final String DEFAULT_DISPLAY_NAME = "Default (LAN)";

        private final List<TenantRecord> records = new ArrayList<>();

        @Override
        public Optional<TenantRecord> lookup(UUID tenantId) {
            return records.stream().filter(r -> r.tenantId().equals(tenantId)).findFirst();
        }

        @Override
        public void register(UUID tenantId, String displayName) {
            records.add(new TenantRecord(tenantId, displayName));
        }

        @Override
        public List<TenantRecord> findAll() {
            return List.copyOf(records);
        }

        /**
         * Returns the UUID of the unique default tenant (displayName "Default (LAN)").
         *
         * <p>AC2 (DEC-24): cross-references DEC-24 for mandate, caching semantics: this stub does
         * not cache (Wave-1 test scope).
         *
         * @throws IllegalStateException if no default tenant is registered
         * @throws IllegalStateException if multiple default tenants are registered
         */
        @Override
        public UUID getDefault() {
            List<TenantRecord> defaults =
                    records.stream()
                            .filter(r -> DEFAULT_DISPLAY_NAME.equals(r.displayName()))
                            .toList();
            if (defaults.isEmpty()) {
                throw new IllegalStateException(
                        "no default tenant registered — bootstrap not complete");
            }
            if (defaults.size() > 1) {
                throw new IllegalStateException("registry violates single-default invariant");
            }
            return defaults.get(0).tenantId();
        }
    }

    // -------------------------------------------------------------------------
    // AC3 — getDefault returns the UUID of the default tenant
    // -------------------------------------------------------------------------

    /**
     * AC3: when exactly one default tenant is registered, {@code getDefault()} returns its UUID.
     */
    @Test
    void getDefaultReturnsUuidOfUniqueDefaultTenant() {
        StubTenantRegistryPort port = new StubTenantRegistryPort();
        UUID defaultId = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000001");
        port.register(defaultId, "Default (LAN)");

        UUID result = port.getDefault();

        assertThat(result)
                .as("getDefault() must return the UUID of the unique 'Default (LAN)' tenant (AC3)")
                .isEqualTo(defaultId);
    }

    // -------------------------------------------------------------------------
    // AC3 — zero default tenants → IllegalStateException
    // -------------------------------------------------------------------------

    /**
     * AC3: when no default tenant is registered, {@code getDefault()} throws {@link
     * IllegalStateException} with message "no default tenant registered".
     */
    @Test
    void getDefaultThrowsWhenNoDefaultTenantRegistered() {
        StubTenantRegistryPort port = new StubTenantRegistryPort();
        // No tenants registered at all

        assertThatThrownBy(port::getDefault)
                .as(
                        "getDefault() must throw IllegalStateException when no default tenant is"
                                + " present (AC3)")
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("no default tenant registered");
    }

    // -------------------------------------------------------------------------
    // AC3 — zero default tenants even with non-default tenants present
    // -------------------------------------------------------------------------

    /** AC3: {@code getDefault()} throws even when non-default tenants exist. */
    @Test
    void getDefaultThrowsWhenOnlyNonDefaultTenantsRegistered() {
        StubTenantRegistryPort port = new StubTenantRegistryPort();
        port.register(UUID.randomUUID(), "Tenant A");
        port.register(UUID.randomUUID(), "Tenant B");

        assertThatThrownBy(port::getDefault)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("no default tenant registered");
    }

    // -------------------------------------------------------------------------
    // AC3 — multiple default tenants → IllegalStateException (single-default invariant)
    // -------------------------------------------------------------------------

    /**
     * AC3: when multiple default tenants are registered (invariant violation), {@code getDefault()}
     * throws {@link IllegalStateException} with message "registry violates single-default
     * invariant".
     */
    @Test
    void getDefaultThrowsWhenMultipleDefaultTenantsRegistered() {
        StubTenantRegistryPort port = new StubTenantRegistryPort();
        port.register(UUID.randomUUID(), "Default (LAN)");
        port.register(UUID.randomUUID(), "Default (LAN)"); // duplicate — invariant violation

        assertThatThrownBy(port::getDefault)
                .as(
                        "getDefault() must throw when multiple default tenants exist"
                                + " (single-default invariant, AC3)")
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("registry violates single-default invariant");
    }

    // -------------------------------------------------------------------------
    // AC3 — non-default tenant present alongside default — only default returned
    // -------------------------------------------------------------------------

    /**
     * AC3: when both a default and a non-default tenant are registered, {@code getDefault()}
     * returns only the default tenant's UUID.
     */
    @Test
    void getDefaultIgnoresNonDefaultTenantsAndReturnsOnlyDefault() {
        StubTenantRegistryPort port = new StubTenantRegistryPort();
        UUID otherTenantId = UUID.fromString("bbbbbbbb-0000-0000-0000-000000000002");
        UUID defaultId = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000001");
        port.register(otherTenantId, "Other Tenant");
        port.register(defaultId, "Default (LAN)");

        UUID result = port.getDefault();

        assertThat(result).isEqualTo(defaultId);
        assertThat(result).isNotEqualTo(otherTenantId);
    }
}
