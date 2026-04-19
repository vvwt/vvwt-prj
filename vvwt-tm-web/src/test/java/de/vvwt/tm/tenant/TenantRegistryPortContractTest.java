package de.vvwt.tm.tenant;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Contract tests for {@link TenantRegistryPort}.
 *
 * <p>Uses a hand-rolled test double — no Mockito (AC7, DEC-22 anti-patterns ref).
 *
 * <p>Acceptance criteria covered:
 *
 * <ul>
 *   <li>AC1 — test-first discipline: this file exists BEFORE the interface
 *   <li>AC3 — lookup-by-identifier: known → {@code Optional} with value; unknown → {@code
 *       Optional.empty()}. This is explicitly distinct from {@link
 *       TenantDataSourceResolver#resolve(UUID)}, which throws on unknown tenants (documented
 *       distinction per AC3).
 *   <li>AC8 — Javadoc coverage (verified in interface source)
 * </ul>
 *
 * <p>Story: E14S01 — DEC-20/DEC-21/DEC-22.
 */
class TenantRegistryPortContractTest {

    // -------------------------------------------------------------------------
    // Test double
    // -------------------------------------------------------------------------

    /**
     * In-memory test double for {@link TenantRegistryPort}. Known tenants return a {@code
     * TenantRecord}; unknown tenants return {@code Optional.empty()}.
     */
    static class MapTenantRegistryPort implements TenantRegistryPort {

        private final Map<UUID, TenantRecord> registry = new HashMap<>();

        @Override
        public void register(UUID tenantId, String displayName) {
            registry.put(tenantId, new TenantRecord(tenantId, displayName));
        }

        @Override
        public Optional<TenantRecord> lookup(UUID tenantId) {
            return Optional.ofNullable(registry.get(tenantId));
        }

        @Override
        public List<TenantRecord> findAll() {
            return new ArrayList<>(registry.values());
        }

        @Override
        public UUID getDefault() {
            List<TenantRecord> defaults =
                    registry.values().stream()
                            .filter(r -> "Default (LAN)".equals(r.displayName()))
                            .toList();
            if (defaults.isEmpty()) {
                throw new IllegalStateException(
                        "no default tenant registered \u2014 bootstrap not complete");
            }
            if (defaults.size() > 1) {
                throw new IllegalStateException("registry violates single-default invariant");
            }
            return defaults.get(0).tenantId();
        }
    }

    // -------------------------------------------------------------------------
    // AC3 — lookup known tenant returns Optional with value
    // -------------------------------------------------------------------------

    /**
     * AC3: {@code lookup()} for a known tenant MUST return a non-empty {@link Optional} containing
     * the tenant record.
     */
    @Test
    void lookupKnownTenantReturnsNonEmptyOptional() {
        MapTenantRegistryPort port = new MapTenantRegistryPort();
        UUID tenantId = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000001");
        port.register(tenantId, "Test Tenant");

        Optional<TenantRegistryPort.TenantRecord> result = port.lookup(tenantId);

        assertThat(result)
                .as("lookup() for a known tenant must return a non-empty Optional (AC3)")
                .isPresent();
        assertThat(result.get().tenantId())
                .as("TenantRecord must contain the queried tenant UUID")
                .isEqualTo(tenantId);
        assertThat(result.get().displayName())
                .as("TenantRecord must contain the registered display name")
                .isEqualTo("Test Tenant");
    }

    // -------------------------------------------------------------------------
    // AC3 — lookup unknown tenant returns Optional.empty
    // -------------------------------------------------------------------------

    /**
     * AC3: {@code lookup()} for an unknown tenant MUST return {@link Optional#empty()}. This is the
     * intentional contract difference from {@link TenantDataSourceResolver#resolve(UUID)}, which
     * throws — the distinction is: registry lookup is an existence check; resolution is routing.
     */
    @Test
    void lookupUnknownTenantReturnsEmptyOptional() {
        MapTenantRegistryPort port = new MapTenantRegistryPort();
        UUID unknownId = UUID.fromString("bbbbbbbb-0000-0000-0000-000000000002");

        Optional<TenantRegistryPort.TenantRecord> result = port.lookup(unknownId);

        assertThat(result)
                .as(
                        "lookup() for an unknown tenant must return Optional.empty() — "
                                + "not throw, not return null (AC3 distinction from resolve())")
                .isEmpty();
    }

    // -------------------------------------------------------------------------
    // AC3 — TenantRecord exposes UUID-based tenant identifier (DEC-17)
    // -------------------------------------------------------------------------

    /**
     * DEC-17: the tenant identifier in the registry is UUID-based. Verifies that {@link
     * TenantRegistryPort.TenantRecord#tenantId()} returns a {@link UUID}.
     */
    @Test
    void tenantRecordExposesUuidBasedTenantId() {
        MapTenantRegistryPort port = new MapTenantRegistryPort();
        UUID tenantId = UUID.fromString("cccccccc-0000-0000-0000-000000000003");
        port.register(tenantId, "UUID Tenant");

        TenantRegistryPort.TenantRecord record = port.lookup(tenantId).orElseThrow();

        assertThat(record.tenantId())
                .as("TenantRecord.tenantId() must be UUID-based (DEC-17)")
                .isInstanceOf(UUID.class)
                .isEqualTo(tenantId);
    }
}
