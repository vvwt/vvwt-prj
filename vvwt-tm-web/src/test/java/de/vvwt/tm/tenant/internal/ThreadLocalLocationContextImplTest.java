// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
package de.vvwt.tm.tenant.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.vvwt.tm.tenant.LocationContext;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link ThreadLocalLocationContextImpl} — verifies the AC3 nested-bind contract for
 * {@link LocationContext} (E14S09).
 *
 * <p>Mirrors the contract from {@link ThreadLocalTenantContextImplTest} for symmetry (E14S09
 * requires LocationContext to have the same nested-bind semantics as TenantContext).
 *
 * @see ThreadLocalLocationContextImpl
 * @see LocationContext
 */
class ThreadLocalLocationContextImplTest {

    private final LocationContext ctx = new ThreadLocalLocationContextImpl();

    // -------------------------------------------------------------------------
    // AC3 — current() throws when unbound
    // -------------------------------------------------------------------------

    @Test
    void current_throwsIllegalStateException_whenNothingBound() {
        assertThatThrownBy(ctx::current)
                .as("AC3: current() must throw when no location is bound")
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("No location");
    }

    // -------------------------------------------------------------------------
    // AC3 — basic bind + current + close restores unbound state
    // -------------------------------------------------------------------------

    @Test
    @SuppressWarnings(
            "try") // scope opened for RAII side-effect (bind+auto-restore); not referenced in body
    // by design (E18S01/DEC-29)
    void bind_and_close_roundtrip() {
        UUID locationId = UUID.randomUUID();

        try (LocationContext.Scope scope = ctx.bind(locationId)) {
            assertThat(ctx.current())
                    .as("AC3: current() must return the bound location inside scope")
                    .isEqualTo(locationId);
        }

        assertThatThrownBy(ctx::current)
                .as("AC3: after scope closes, current() must throw (unbound)")
                .isInstanceOf(IllegalStateException.class);
    }

    // -------------------------------------------------------------------------
    // AC3 — nested bind: inner overrides outer; close restores outer
    // -------------------------------------------------------------------------

    @Test
    @SuppressWarnings(
            "try") // outerScope/innerScope opened for RAII side-effect (bind+auto-restore); not
    // referenced in body by design (E18S01/DEC-29)
    void nestedBind_innerOverridesOuter_closeRestoresOuter() {
        UUID outer = UUID.randomUUID();
        UUID inner = UUID.randomUUID();

        try (LocationContext.Scope outerScope = ctx.bind(outer)) {
            assertThat(ctx.current()).as("outer scope active").isEqualTo(outer);

            try (LocationContext.Scope innerScope = ctx.bind(inner)) {
                assertThat(ctx.current())
                        .as("AC3: inner bind must override outer for inner scope only")
                        .isEqualTo(inner);
            }

            assertThat(ctx.current())
                    .as("AC3: after inner scope closes, outer scope must be restored")
                    .isEqualTo(outer);
        }

        assertThatThrownBy(ctx::current)
                .as("AC3: after outer scope closes, context must be unbound again")
                .isInstanceOf(IllegalStateException.class);
    }

    // -------------------------------------------------------------------------
    // AC3 — bind(null) throws IllegalArgumentException
    // -------------------------------------------------------------------------

    @Test
    void bind_null_throwsIllegalArgumentException() {
        assertThatThrownBy(() -> ctx.bind(null))
                .as("AC3: bind(null) must throw IllegalArgumentException")
                .isInstanceOf(IllegalArgumentException.class);
    }
}
