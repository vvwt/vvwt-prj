package de.vvwt.info.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;

import de.vvwt.info.ratelimit.config.RateLimitProperties;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link RateLimitProperties} validation.
 *
 * <p>DEC-22 RED-first: tests written before production code.
 *
 * @see <a href="../../../../../../../../docs/governance/stories/E38S07.story.md">E38S07 AC5,
 *     AC12</a>
 */
class RateLimitPropertiesTest {

    private Validator validator;

    @BeforeEach
    void setUp() {
        validator = Validation.buildDefaultValidatorFactory().getValidator();
    }

    @Test
    void validProperties_noConstraintViolations() {
        RateLimitProperties props = new RateLimitProperties();
        RateLimitProperties.PerIp perIp = new RateLimitProperties.PerIp();
        perIp.setPublisherRpm(600);
        perIp.setReaderPollRpm(1200);
        perIp.setReaderWsRpm(300);
        props.setPerIp(perIp);
        RateLimitProperties.PerTournamentToken perTournament =
                new RateLimitProperties.PerTournamentToken();
        perTournament.setMaxConcurrentWs(1000);
        props.setPerTournamentToken(perTournament);
        props.setTrustedProxies(List.of());

        Set<ConstraintViolation<RateLimitProperties>> violations = validator.validate(props);
        assertThat(violations).isEmpty();
    }

    @Test
    void publisherRpmZero_violatesMinConstraint() {
        RateLimitProperties props = validProps();
        props.getPerIp().setPublisherRpm(0);

        Set<ConstraintViolation<RateLimitProperties>> violations = validator.validate(props);
        assertThat(violations).isNotEmpty();
        assertThat(violations)
                .anyMatch(v -> v.getPropertyPath().toString().contains("publisherRpm"));
    }

    @Test
    void publisherRpmNegative_violatesMinConstraint() {
        RateLimitProperties props = validProps();
        props.getPerIp().setPublisherRpm(-1);

        Set<ConstraintViolation<RateLimitProperties>> violations = validator.validate(props);
        assertThat(violations).isNotEmpty();
    }

    @Test
    void readerPollRpmZero_violatesMinConstraint() {
        RateLimitProperties props = validProps();
        props.getPerIp().setReaderPollRpm(0);

        Set<ConstraintViolation<RateLimitProperties>> violations = validator.validate(props);
        assertThat(violations).isNotEmpty();
    }

    @Test
    void readerWsRpmZero_violatesMinConstraint() {
        RateLimitProperties props = validProps();
        props.getPerIp().setReaderWsRpm(0);

        Set<ConstraintViolation<RateLimitProperties>> violations = validator.validate(props);
        assertThat(violations).isNotEmpty();
    }

    @Test
    void maxConcurrentWsZero_violatesMinConstraint() {
        RateLimitProperties props = validProps();
        props.getPerTournamentToken().setMaxConcurrentWs(0);

        Set<ConstraintViolation<RateLimitProperties>> violations = validator.validate(props);
        assertThat(violations).isNotEmpty();
    }

    @Test
    void trustedProxiesNull_violatesNotNullConstraint() {
        RateLimitProperties props = validProps();
        props.setTrustedProxies(null);

        Set<ConstraintViolation<RateLimitProperties>> violations = validator.validate(props);
        assertThat(violations).isNotEmpty();
        assertThat(violations)
                .anyMatch(v -> v.getPropertyPath().toString().contains("trustedProxies"));
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private RateLimitProperties validProps() {
        RateLimitProperties props = new RateLimitProperties();
        RateLimitProperties.PerIp perIp = new RateLimitProperties.PerIp();
        perIp.setPublisherRpm(600);
        perIp.setReaderPollRpm(1200);
        perIp.setReaderWsRpm(300);
        props.setPerIp(perIp);
        RateLimitProperties.PerTournamentToken perTournament =
                new RateLimitProperties.PerTournamentToken();
        perTournament.setMaxConcurrentWs(1000);
        props.setPerTournamentToken(perTournament);
        props.setTrustedProxies(List.of());
        return props;
    }
}
