package de.vvwt.tm.tenant;

import de.vvwt.tm.TournamentManagerApplication;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for {@link TenantContextTestSupport} — the shared {@code @TestConfiguration}
 * that auto-binds the default-tenant {@link TenantContext} for every {@code @SpringBootTest}.
 *
 * <h2>TDD Red-Green discipline (AC1 / DEC-22)</h2>
 * <p>This test class was committed BEFORE {@link TenantContextTestSupport} was implemented.
 * In the RED state the class reference does not compile (compilation failure = valid RED indicator
 * per DEC-22 Iron Law — the test cannot pass before the implementation exists).
 *
 * <h2>Test coverage</h2>
 * <ul>
 *   <li>AC1  — test-first: this file is committed before the implementation (RED)</li>
 *   <li>AC6  — {@code TenantContext.current()} returns a non-null UUID equal to the default-tenant UUID</li>
 *   <li>AC9  — back-to-back cleanup: second test asserts clean re-bind, no leaked state from first</li>
 *   <li>AC3  — nested bind: inner {@code bind()} overrides outer; closing inner scope restores outer</li>
 * </ul>
 *
 * @see TenantContextTestSupport
 * @see de.vvwt.tm.tenant.TenantContext
 * @see <a href="../../../../../../../../docs/governance/stories/E14S10.story.md">Story E14S10</a>
 * @see <a href="../../../../../../../../docs/governance/decisions/DEC-20.md">DEC-20 (DB-per-Tenant)</a>
 * @see <a href="../../../../../../../../docs/governance/decisions/DEC-21.md">DEC-21 (Modulith)</a>
 * @see <a href="../../../../../../../../docs/governance/decisions/DEC-22.md">DEC-22 (TDD)</a>
 */
@SpringBootTest(
        classes = TournamentManagerApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
@Import(TenantContextTestSupport.class)
class TenantContextTestSupportIT {

    @Autowired
    @Qualifier("tenantRoutingContext")
    private TenantContext tenantContext;

    @Autowired
    private TenantContextTestSupport.Binder binder;

    // -------------------------------------------------------------------------
    // AC6 — TenantContext.current() returns the default-tenant UUID
    // -------------------------------------------------------------------------

    /**
     * AC6 — Within a {@code @SpringBootTest} method body, {@code TenantContext.current()} returns
     * a non-null UUID matching the default-tenant UUID resolved from the E14S05 bootstrap.
     */
    @Test
    void currentReturnsDefaultTenantUuidAfterAutoBind() {
        UUID boundId = binder.bindDefaultTenant();
        try {
            assertThat(tenantContext.current())
                    .as("TenantContext.current() must return the default-tenant UUID after auto-bind")
                    .isNotNull()
                    .isEqualTo(boundId);
        } finally {
            binder.unbind();
        }
    }

    // -------------------------------------------------------------------------
    // AC9 — back-to-back cleanup: method 2 sees a clean re-bind, not leaked state
    // -------------------------------------------------------------------------

    /**
     * AC9 (first method of back-to-back pair) — binds, asserts, and releases cleanly.
     */
    @Test
    void backToBackFirstMethod_bindsAndUnbinds() {
        UUID boundId = binder.bindDefaultTenant();
        try {
            assertThat(tenantContext.current()).isEqualTo(boundId);
        } finally {
            binder.unbind();
        }
    }

    /**
     * AC9 (second method of back-to-back pair) — after the first method released the binding,
     * a fresh bind succeeds and returns the same default-tenant UUID (no leaked state).
     */
    @Test
    void backToBackSecondMethod_freshBindSucceeds() {
        UUID boundId = binder.bindDefaultTenant();
        try {
            assertThat(tenantContext.current())
                    .as("Second test must re-bind cleanly without leaked state from prior test")
                    .isEqualTo(boundId);
        } finally {
            binder.unbind();
        }
    }

    // -------------------------------------------------------------------------
    // AC3 — nested-bind: inner bind overrides outer; closing inner scope restores outer
    // -------------------------------------------------------------------------

    /**
     * AC3 — nested bind behaviour: an inner {@link TenantContext#bind(UUID)} overrides the outer
     * for its scope only; closing the inner scope restores the outer tenant
     * (AC-NESTED-BIND from E14S01).
     */
    @Test
    void nestedBind_innerOverridesOuter_closingInnerRestoresOuter() {
        UUID outerTenantId = binder.bindDefaultTenant();
        try {
            UUID innerTenantId = UUID.randomUUID();
            assertThat(innerTenantId).isNotEqualTo(outerTenantId);

            // Inner bind overrides outer
            try (TenantContext.Scope innerScope = tenantContext.bind(innerTenantId)) {
                assertThat(tenantContext.current())
                        .as("Inner bind must override outer for inner's scope")
                        .isEqualTo(innerTenantId);
            }

            // After inner scope closes, outer is restored
            assertThat(tenantContext.current())
                    .as("After inner scope closes, outer tenant must be restored")
                    .isEqualTo(outerTenantId);
        } finally {
            binder.unbind();
        }
    }
}
