package de.vvwt.tm.tenant;

import static org.assertj.core.api.Assertions.assertThat;

import de.vvwt.tm.TournamentManagerApplication;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

/**
 * Canary regression guard for E16S02 — proves that {@code application-test.yml} no longer
 * hard-codes the shared {@code jdbc:h2:mem:testdb;DB_CLOSE_DELAY=-1} URL that caused
 * ordering-dependent Flyway collision flakiness.
 *
 * <h2>Red-Green discipline (DEC-22 Iron Law)</h2>
 *
 * <p>This test was committed <strong>RED</strong> against pre-fix {@code application-test.yml}
 * (HEAD {@code 321fdacdb89746c744143b6d2f467e639cf9ad3d}):
 *
 * <ul>
 *   <li>{@link #datasourceUrl_absent_afterFix_noHardcodedSharedName()} failed because {@link
 *       DataSourceProperties#getUrl()} returned the hard-coded {@code
 *       jdbc:h2:mem:testdb;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE} (i.e. was not {@code null}).
 * </ul>
 *
 * <p>The tests turn <strong>GREEN</strong> after the fix: the explicit {@code url} property is
 * removed from {@code application-test.yml} and replaced with {@code
 * spring.datasource.generate-unique-name=true}. Spring Boot then generates a UUID-based H2 database
 * name for each new {@link org.springframework.context.ApplicationContext} — {@link
 * DataSourceProperties#getUrl()} returns {@code null} and {@link
 * DataSourceProperties#isGenerateUniqueName()} is the effective mechanism.
 *
 * <h2>Root-cause mechanism (E16S02 § Context)</h2>
 *
 * <p>{@code DB_CLOSE_DELAY=-1} keeps the H2 in-memory database alive for the JVM lifetime. When
 * multiple {@code @SpringBootTest} classes share the {@code test} profile and resolve to the same
 * {@code testdb} URL, Flyway runs against the same pre-existing H2 instance across context loads.
 * If the class order places a context that expects a fresh migration after one that already
 * migrated, Flyway detects conflicting schema state ({@code admin_credentials} already present) and
 * errors. This failure is non-deterministic across Surefire class orderings (self-masked per H-3 in
 * the E16S02 Session Brief).
 *
 * <h2>AC4 evidence (per-ApplicationContext independence)</h2>
 *
 * <p>After the fix, no explicit {@code url} property is set in the {@code test} profile YAML.
 * Spring Boot generates a distinct UUID-based H2 name for every new {@code ApplicationContext}
 * load. Schema state created by context A is NOT visible from context B because they each receive
 * an independent H2 database.
 *
 * <h2>Governance</h2>
 *
 * <ul>
 *   <li>This test is a <strong>permanent regression guard</strong> — not a temporary fixture. Its
 *       presence prevents re-introduction of the shared {@code testdb} URL (Story E16S02, AC1,
 *       canary option (a) — retained as permanent IT).
 *   <li>DEC-26 does not govern {@code @SpringBootTest} ITs (its three rules are scoped to DAO ITs
 *       via {@code TenantDaoTestSupport}). This canary is a profile-level governance test.
 *   <li>DEC-22 Iron Law: RED commit precedes GREEN commit in git history.
 * </ul>
 *
 * @see CrossTenantIsolationTest
 * @see <a href="../../../../../../../../docs/governance/stories/E16S02.story.md">Story E16S02</a>
 * @see <a href="../../../../../../../../docs/governance/decisions/DEC-22.md">DEC-22 (TDD Iron
 *     Law)</a>
 * @since E16S02
 */
@SpringBootTest(
        classes = {TournamentManagerApplication.class},
        webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
@Import(TenantContextTestSupport.class)
@DisplayName("E16S02 canary — shared testdb URL absent after generate-unique-name=true fix")
class SharedTestDbFlakinessCanaryIT {

    @Autowired private DataSourceProperties dataSourceProperties;

    /**
     * Asserts that no explicit hard-coded DataSource URL containing the shared {@code testdb} name
     * is configured in the {@code test} profile. The post-fix state is either {@code null} or
     * empty-string — both mean no explicit URL, enabling {@code generate-unique-name=true} to give
     * each Spring {@code ApplicationContext} an independent H2 instance.
     *
     * <p><strong>RED (pre-fix):</strong> {@code application-test.yml} sets {@code url:
     * "jdbc:h2:mem:testdb;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE"} → {@link
     * DataSourceProperties#getUrl()} returns that hard-coded string → assertion {@code
     * isNullOrEmpty()} fails.
     *
     * <p><strong>GREEN (post-fix):</strong> the explicit URL value is cleared in {@code
     * application-test.yml} (YAML null value overrides the base profile URL); only {@code
     * generate-unique-name: true} remains active → {@link DataSourceProperties#getUrl()} returns
     * {@code null} or empty → assertion passes.
     *
     * <p>AC4 evidence: when no explicit URL is set and {@code generate-unique-name=true} is the
     * mechanism, each Spring {@code ApplicationContext} load receives a UUID-based H2 database name
     * generated at boot time. Independent contexts therefore cannot share the same H2 instance —
     * schema state created by one context is NOT visible from another.
     */
    @Test
    void datasourceUrl_absent_afterFix_noHardcodedSharedName() {
        String configuredUrl = dataSourceProperties.getUrl();

        assertThat(configuredUrl)
                .as(
                        "DataSource URL must be null or empty in the 'test' profile after the"
                            + " E16S02 fix. A non-empty value containing 'testdb' or any hard-coded"
                            + " shared name means the explicit URL is still active, overriding"
                            + " generate-unique-name=true and re-introducing the shared-DB"
                            + " collision risk. FAIL on pre-fix HEAD (URL ="
                            + " 'jdbc:h2:mem:testdb;DB_CLOSE_DELAY=-1;...'); PASS after fix (URL"
                            + " cleared → null or empty). AC4: absent URL +"
                            + " generate-unique-name=true gives each context an independent H2"
                            + " instance.")
                .satisfiesAnyOf(url -> assertThat(url).isNull(), url -> assertThat(url).isEmpty());
    }

    /**
     * Asserts that the hard-coded {@code DB_CLOSE_DELAY=-1} retention flag is absent from the
     * configured DataSource URL.
     *
     * <p>{@code DB_CLOSE_DELAY=-1} was the JVM-lifetime retention mechanism that kept the shared H2
     * database alive across {@code @SpringBootTest} context loads — a prerequisite for the
     * ordering-dependent Flyway collision.
     *
     * <p><strong>RED (pre-fix):</strong> {@code getUrl()} returns {@code
     * "jdbc:h2:mem:testdb;DB_CLOSE_DELAY=-1;..."} → assertion fails.
     *
     * <p><strong>GREEN (post-fix):</strong> {@code getUrl()} returns {@code null} → the condition
     * {@code configuredUrl != null} is false → assertion is a no-op → passes.
     *
     * <p>Together with {@link #datasourceUrl_absent_afterFix_noHardcodedSharedName()}, this test
     * forms the complete canary: both the shared name and the retention flag must be absent.
     */
    @Test
    void datasourceUrl_doesNotContainJvmLifetimeRetentionFlag() {
        String configuredUrl = dataSourceProperties.getUrl();

        if (configuredUrl != null) {
            assertThat(configuredUrl)
                    .as(
                            "DataSource URL must NOT contain DB_CLOSE_DELAY=-1 — the JVM-lifetime"
                                + " retention flag that kept the shared H2 alive across Spring"
                                + " ApplicationContext loads (root cause of E16S02 flakiness). FAIL"
                                + " on pre-fix HEAD; PASS after fix (URL property removed).")
                    .doesNotContain("DB_CLOSE_DELAY=-1");
        }
        // If configuredUrl is null (post-fix), this test is a no-op — the first test
        // datasourceUrl_absent_afterFix_noHardcodedSharedName() is the definitive RED-GREEN gate.
    }
}
