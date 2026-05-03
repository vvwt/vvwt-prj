package de.vvwt.tm.certificate.internal;

import de.vvwt.tm.certificate.LocaleResolver;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Default implementation of {@link LocaleResolver} for the {@code certificate} bounded context
 * (E46S02).
 *
 * <p>Resolves the effective locale for a given team or tournament by walking the language chain:
 * {@code team.language ?? tournament.language ?? tenant.language ?? "de"}. All three reads are
 * executed via intra-DB JDBC against the bound per-tenant DataSource (DEC-20 routing) — no class
 * from the {@code tenant} module is imported directly, preserving {@code
 * certificate.allowedDependencies = {tournament, photo}} (Brief v5 C2=β amendment,
 * AC-NO-NEW-CROSS-CONTEXT-EDGE).
 *
 * <h2>TenantContext precondition</h2>
 *
 * <p>Both methods MUST be invoked from a thread with a bound {@code TenantContext}. The {@code
 * AbstractRoutingDataSource} routes JDBC operations via {@code TenantContext.current()} (DEC-20).
 * Neither method adds its own pre-check; the {@code IllegalStateException} from {@code
 * TenantContext.current()} propagates naturally (AC-RESOLVER-REQUIRES-BOUND-TENANT-CONTEXT).
 *
 * <h2>tenants-row invariant (DEC-5)</h2>
 *
 * <p>Every per-tenant DB has exactly one {@code tenants} row. If the row is absent, this resolver
 * throws an {@code IllegalStateException} identifying the invariant violation
 * (AC-RESOLVER-MISSING-TENANTS-ROW-FAIL-FAST). The chain does NOT silently fall through to "de"
 * when the tenants row is missing.
 *
 * <h2>Blank-as-null semantics</h2>
 *
 * <p>A language value that is empty or whitespace-only is treated as null at each chain step — the
 * chain advances to the next surface (AC-RESOLVER-BLANK-TREATED-AS-NULL).
 *
 * <h2>Naming canon (DEC-35)</h2>
 *
 * <p>{@code DefaultLocaleResolver} implements {@link LocaleResolver} per the {@code Default*}
 * naming convention. This class is package-private (no {@code public} modifier) per the Modulith
 * internal-package convention — only the interface is public (AC-RESOLVER-IMPL-PRESENT).
 *
 * <h2>Thread-safety</h2>
 *
 * <p>Stateless — constructor-injected immutable collaborators, no mutable instance state. Safe for
 * use as a Spring singleton bean.
 *
 * @see LocaleResolver
 * @since E46S02
 */
@Service
class DefaultLocaleResolver implements LocaleResolver {

    private static final String SYSTEM_DEFAULT_LANGUAGE = "de";

    /**
     * Intra-DB JDBC access to the bound per-tenant DataSource (DEC-20 routing via
     * AbstractRoutingDataSource). The DataSource resolves the current tenant from
     * TenantContext.current() at connection-acquisition time — no direct call to TenantContext
     * here.
     */
    private final JdbcTemplate jdbc;

    /**
     * Constructor injection — {@link DataSource} is the routing DataSource (DEC-20). Spring injects
     * the {@code routingTenantDataSource} bean qualified by {@link
     * org.springframework.beans.factory.annotation.Qualifier @Qualifier} at application context
     * startup.
     *
     * @param dataSource the routing DataSource; must not be {@code null}
     */
    DefaultLocaleResolver(DataSource dataSource) {
        this.jdbc = new JdbcTemplate(dataSource);
    }

    /** {@inheritDoc} */
    @Override
    public Locale resolveForTeam(UUID tournamentId, UUID teamId) {
        String teamLanguage = queryTeamLanguage(teamId);
        if (isPresent(teamLanguage)) {
            return Locale.forLanguageTag(teamLanguage);
        }

        String tournamentLanguage = queryTournamentLanguage(tournamentId);
        if (isPresent(tournamentLanguage)) {
            return Locale.forLanguageTag(tournamentLanguage);
        }

        String tenantLanguage = queryTenantLanguage();
        if (isPresent(tenantLanguage)) {
            return Locale.forLanguageTag(tenantLanguage);
        }

        return Locale.forLanguageTag(SYSTEM_DEFAULT_LANGUAGE);
    }

    /** {@inheritDoc} */
    @Override
    public Locale resolveForTournament(UUID tournamentId) {
        String tournamentLanguage = queryTournamentLanguage(tournamentId);
        if (isPresent(tournamentLanguage)) {
            return Locale.forLanguageTag(tournamentLanguage);
        }

        String tenantLanguage = queryTenantLanguage();
        if (isPresent(tenantLanguage)) {
            return Locale.forLanguageTag(tenantLanguage);
        }

        return Locale.forLanguageTag(SYSTEM_DEFAULT_LANGUAGE);
    }

    // -------------------------------------------------------------------------
    // Private JDBC read helpers
    // -------------------------------------------------------------------------

    private String queryTeamLanguage(UUID teamId) {
        List<String> results =
                jdbc.queryForList(
                        "SELECT language FROM team WHERE id = ?", String.class, teamId.toString());
        return results.isEmpty() ? null : results.get(0);
    }

    private String queryTournamentLanguage(UUID tournamentId) {
        List<String> results =
                jdbc.queryForList(
                        "SELECT language FROM tournament WHERE id = ?",
                        String.class,
                        tournamentId.toString());
        return results.isEmpty() ? null : results.get(0);
    }

    /**
     * Reads {@code language} from the {@code tenants} table.
     *
     * <p>Per DEC-5, exactly one row exists per per-tenant DB. If the row is absent (invariant
     * violation), throws {@link IllegalStateException} (AC-RESOLVER-MISSING-TENANTS-ROW-FAIL-FAST).
     *
     * @return the tenant language value (may be null or blank); never aborts on null — only aborts
     *     on missing row
     * @throws IllegalStateException if the tenants table has no rows
     */
    private String queryTenantLanguage() {
        List<String> results = jdbc.queryForList("SELECT language FROM tenants", String.class);
        if (results.isEmpty()) {
            throw new IllegalStateException(
                    "tenants table has no rows — DEC-5 invariant violation: every per-tenant DB "
                            + "must have exactly one tenants row. Cannot resolve tenant language.");
        }
        return results.get(0);
    }

    /**
     * Returns {@code true} if the given language string is non-null and non-blank.
     *
     * <p>Empty-string and whitespace-only values are treated as null
     * (AC-RESOLVER-BLANK-TREATED-AS-NULL).
     */
    private static boolean isPresent(String language) {
        return language != null && !language.isBlank();
    }
}
