package de.vvwt.tm.certificate;

import java.util.Locale;
import java.util.UUID;

/**
 * Public port for locale resolution within the {@code certificate} bounded context (E46S02).
 *
 * <p>Resolves the effective {@link Locale} for a given team or tournament by walking the language
 * chain defined in Brief D-7 (E46 Session Brief v5):
 *
 * <ul>
 *   <li>{@link #resolveForTeam} chain: {@code team.language ?? tournament.language ??
 *       tenant.language ?? "de"}
 *   <li>{@link #resolveForTournament} chain: {@code tournament.language ?? tenant.language ?? "de"}
 * </ul>
 *
 * <p>All three language reads are executed against the bound per-tenant DataSource via intra-DB
 * JDBC (DEC-20 routing, no cross-module import from {@code tenant.*} — the C2=β amendment per Brief
 * v5 preserves {@code certificate.allowedDependencies = {tournament, photo}}).
 *
 * <h2>TenantContext precondition</h2>
 *
 * <p>Both methods MUST be called from a thread that has a bound {@link
 * de.vvwt.tm.tenant.TenantContext} (AC-RESOLVER-REQUIRES-BOUND-TENANT-CONTEXT). The {@code
 * AbstractRoutingDataSource} routes JDBC reads through {@code TenantContext.current()}; calling
 * either method outside a bound context propagates the {@code IllegalStateException} from {@code
 * TenantContext.current()}.
 *
 * <h2>Naming canon (DEC-35)</h2>
 *
 * <p>Interface name follows the DEC-35 {@code {Foo}} convention (no {@code I}-prefix). The
 * canonical implementation is {@code de.vvwt.tm.certificate.internal.DefaultLocaleResolver}.
 *
 * <h2>Interface purity</h2>
 *
 * <p>This interface declares NO implementation-leaking types: no {@code JdbcTemplate} import, no
 * Spring annotations, no {@code @Service} (AC-RESOLVER-INTERFACE-PRESENT).
 *
 * @see de.vvwt.tm.certificate.internal.DefaultLocaleResolver
 * @since E46S02
 */
public interface LocaleResolver {

    /**
     * Resolves the effective locale for a specific team in a specific tournament.
     *
     * <p>Resolution chain (AC-RESOLVER-CHAIN-CORRECT-FOR-TEAM):
     *
     * <ol>
     *   <li>{@code team.language} — if non-null and non-blank, returns {@link
     *       Locale#forLanguageTag(String) Locale.forLanguageTag(team.language)}.
     *   <li>{@code tournament.language} — if non-null and non-blank, returns {@link
     *       Locale#forLanguageTag(String) Locale.forLanguageTag(tournament.language)}.
     *   <li>{@code tenant.language} — read via intra-DB JDBC from the {@code tenants} table of the
     *       bound per-tenant DataSource (exactly one row per DEC-5). If non-null and non-blank,
     *       returns {@link Locale#forLanguageTag(String) Locale.forLanguageTag(tenant.language)}.
     *   <li>System default — returns {@link Locale#forLanguageTag(String)
     *       Locale.forLanguageTag("de")} (Brief C-15).
     * </ol>
     *
     * <p>Empty-string and whitespace-only values are treated as null at each step
     * (AC-RESOLVER-BLANK-TREATED-AS-NULL).
     *
     * @param tournamentId the tournament UUID; must not be {@code null}
     * @param teamId the team UUID; must not be {@code null}
     * @return the resolved {@link Locale}; never {@code null}
     * @throws IllegalStateException if no {@code TenantContext} is bound to the calling thread
     *     (propagated from {@code TenantContext.current()} —
     *     AC-RESOLVER-REQUIRES-BOUND-TENANT-CONTEXT), or if the {@code tenants} table has no rows
     *     (invariant violation per DEC-5 — AC-RESOLVER-MISSING-TENANTS-ROW-FAIL-FAST)
     */
    Locale resolveForTeam(UUID tournamentId, UUID teamId);

    /**
     * Resolves the effective locale for a tournament (chrome / document-level context where no
     * single team is in scope).
     *
     * <p>Resolution chain (AC-RESOLVER-CHAIN-CORRECT-FOR-TOURNAMENT):
     *
     * <ol>
     *   <li>{@code tournament.language} — if non-null and non-blank.
     *   <li>{@code tenant.language} — read via intra-DB JDBC from the {@code tenants} table.
     *   <li>System default — {@link Locale#forLanguageTag(String) Locale.forLanguageTag("de")}.
     * </ol>
     *
     * <p>The team surface is NOT consulted in this overload.
     *
     * @param tournamentId the tournament UUID; must not be {@code null}
     * @return the resolved {@link Locale}; never {@code null}
     * @throws IllegalStateException if no {@code TenantContext} is bound, or if the {@code tenants}
     *     table has no rows
     */
    Locale resolveForTournament(UUID tournamentId);
}
