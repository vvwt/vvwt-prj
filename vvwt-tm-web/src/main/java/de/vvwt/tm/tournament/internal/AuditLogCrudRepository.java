package de.vvwt.tm.tournament.internal;

/**
 * Package-placement marker for {@link AuditLogEntry} persistence (DEC-21, DEC-22, E21S05).
 *
 * <p>INTERNAL to the {@code tournament} Modulith context per inventory line 286. Not a public API
 * surface.
 *
 * <p><b>Activation note:</b> This interface intentionally does NOT extend {@code CrudRepository}.
 * Both {@code de.vvwt.tm.tournament.AuditLogEntry} and the legacy {@code
 * de.vvwt.tm.domain.AuditLogEntry} are mapped to {@code @Table("audit_log")}. Spring Data JDBC's
 * auto-registration of any {@code CrudRepository} for either entity causes bean-override collisions
 * that break the legacy {@code de.vvwt.tm.domain.repo.AuditLogRepository}. This interface exists
 * for package-placement compliance (inventory line 286) only — {@link AuditLogRepository} uses
 * JdbcTemplate directly until the E21S13 atomic cutover deletes the legacy entity.
 *
 * @see AuditLogRepository
 * @see AuditLogEntry
 * @see <a href="DEC-21">DEC-21 — internal package discipline</a>
 * @see <a href="E21S05">E21S05 — inventory line 286</a>
 */
interface AuditLogCrudRepository {
    // Package-placement marker only.
    // No Spring Data JDBC auto-registration — see Javadoc above.
}
