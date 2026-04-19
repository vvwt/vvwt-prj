package de.vvwt.tm.domain.rules;

import static org.assertj.core.api.Assertions.*;

import de.vvwt.tm.domain.Tournament;
import de.vvwt.tm.tenant.TenantContextTestSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

/**
 * Integration test for {@link TournamentRuleResolver} and {@link SetValidationRuleRegistry} (AC9,
 * AC10).
 *
 * <p>Loads the full Spring application context and verifies:
 *
 * <ul>
 *   <li>AC9: {@code "standardVolleyball"} resolves to {@link StandardVolleyballSet}
 *   <li>AC9: {@code "timeBounded"} resolves to {@link TimeBoundedSet}
 *   <li>AC10: unknown id fails fast with {@link IllegalArgumentException} naming the unknown id and
 *       the list of known ids
 * </ul>
 */
@SpringBootTest(
        properties = {
            "spring.datasource.url=jdbc:h2:mem:ruleResolverDb;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE"
                + ";CASE_INSENSITIVE_IDENTIFIERS=TRUE"
        })
@Import(TenantContextTestSupport.class)
@DisplayName("TournamentRuleResolver integration")
class TournamentRuleResolverIT {

    @Autowired private TournamentRuleResolver resolver;

    @Autowired private SetValidationRuleRegistry registry;

    // -----------------------------------------------------------------------
    // AC9 — correct bean resolution via Tournament
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("AC9: 'standardVolleyball' resolves to StandardVolleyballSet")
    void standardVolleyballResolvesToCorrectBean() {
        Tournament tournament = new Tournament();
        tournament.setSetValidationRuleId("standardVolleyball");
        SetValidationRule rule = resolver.resolveSetValidationRule(tournament);
        assertThat(rule).isInstanceOf(StandardVolleyballSet.class);
        assertThat(rule.getBeanId()).isEqualTo("standardVolleyball");
    }

    @Test
    @DisplayName("AC9: 'timeBounded' resolves to TimeBoundedSet")
    void timeBoundedResolvesToCorrectBean() {
        Tournament tournament = new Tournament();
        tournament.setSetValidationRuleId("timeBounded");
        SetValidationRule rule = resolver.resolveSetValidationRule(tournament);
        assertThat(rule).isInstanceOf(TimeBoundedSet.class);
        assertThat(rule.getBeanId()).isEqualTo("timeBounded");
    }

    // -----------------------------------------------------------------------
    // AC10 — unknown rule id fails fast at resolution time
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("AC10: unknown rule id → IllegalArgumentException naming the id and known ids")
    void unknownRuleIdFailsFast() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> registry.get("unknownRule"))
                .withMessageContaining("unknownRule")
                .withMessageContaining("standardVolleyball")
                .withMessageContaining("timeBounded");
    }

    @Test
    @DisplayName("AC10: resolver with unknown id → IllegalArgumentException")
    void resolverWithUnknownIdFailsFast() {
        Tournament tournament = new Tournament();
        tournament.setSetValidationRuleId("unknownRule");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> resolver.resolveSetValidationRule(tournament))
                .withMessageContaining("unknownRule");
    }

    // -----------------------------------------------------------------------
    // AC5 — registry contains both expected beans
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("AC5: registry contains 'standardVolleyball' and 'timeBounded'")
    void registryContainsBothBeans() {
        assertThat(registry.getAll())
                .containsKey("standardVolleyball")
                .containsKey("timeBounded")
                .hasSize(2);
    }
}
