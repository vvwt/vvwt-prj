package de.vvwt.info.registration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.vvwt.info.persistence.invitation.ConsumedInvitationTokenDao;
import de.vvwt.info.persistence.invitation.ConsumedInvitationTokenRecord;
import de.vvwt.info.registration.config.RegistrationProperties;
import de.vvwt.info.registration.internal.DefaultInvitationTokenPool;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * Unit tests for {@link InvitationTokenPool}.
 *
 * <p>DEC-22 Q-1a TDD RED-first (AC1, AC10).
 *
 * @see <a href="../../../../../../../../docs/governance/stories/E38S04.story.md">E38S04 AC10</a>
 */
class InvitationTokenPoolTest {

    private static RegistrationProperties propsWithTokens(String... tokens) {
        RegistrationProperties props = new RegistrationProperties();
        props.setInvitationTokens(List.of(tokens));
        return props;
    }

    @Test
    void initialize_noConsumedTokens_allTokensAvailable() {
        var dao = mock(ConsumedInvitationTokenDao.class);
        when(dao.findAll()).thenReturn(List.of());

        var pool =
                new DefaultInvitationTokenPool(
                        propsWithTokens("TOKEN_A", "TOKEN_B"), dao, Clock.systemUTC());
        pool.initialize();

        assertThat(pool.isAvailable("TOKEN_A")).isTrue();
        assertThat(pool.isAvailable("TOKEN_B")).isTrue();
    }

    @Test
    void initialize_someConsumedTokens_removesConsumed() {
        var dao = mock(ConsumedInvitationTokenDao.class);
        when(dao.findAll())
                .thenReturn(
                        List.of(
                                new ConsumedInvitationTokenRecord(
                                        "TOKEN_A", LocalDateTime.now(), null)));

        var pool =
                new DefaultInvitationTokenPool(
                        propsWithTokens("TOKEN_A", "TOKEN_B"), dao, Clock.systemUTC());
        pool.initialize();

        assertThat(pool.isAvailable("TOKEN_A")).isFalse();
        assertThat(pool.isAvailable("TOKEN_B")).isTrue();
    }

    @Test
    void isAvailable_unknownToken_returnsFalse() {
        var dao = mock(ConsumedInvitationTokenDao.class);
        when(dao.findAll()).thenReturn(List.of());

        var pool =
                new DefaultInvitationTokenPool(propsWithTokens("TOKEN_A"), dao, Clock.systemUTC());
        pool.initialize();

        assertThat(pool.isAvailable("UNKNOWN")).isFalse();
    }

    @Test
    void isAvailable_nullToken_returnsFalse() {
        var dao = mock(ConsumedInvitationTokenDao.class);
        when(dao.findAll()).thenReturn(List.of());

        var pool =
                new DefaultInvitationTokenPool(propsWithTokens("TOKEN_A"), dao, Clock.systemUTC());
        pool.initialize();

        assertThat(pool.isAvailable(null)).isFalse();
    }

    @Test
    void consumeToken_availableToken_removesFromPoolAndPersists() {
        var dao = mock(ConsumedInvitationTokenDao.class);
        when(dao.findAll()).thenReturn(List.of());

        var fixedClock = Clock.fixed(Instant.parse("2026-04-27T12:00:00Z"), ZoneOffset.UTC);

        var pool = new DefaultInvitationTokenPool(propsWithTokens("TOKEN_X"), dao, fixedClock);
        pool.initialize();

        pool.consumeToken("TOKEN_X", "tenant-1");

        // Token should no longer be available after consumption
        assertThat(pool.isAvailable("TOKEN_X")).isFalse();

        // Verify the DAO was called with the correct record
        var captor = ArgumentCaptor.forClass(ConsumedInvitationTokenRecord.class);
        verify(dao).save(captor.capture());
        assertThat(captor.getValue().tokenValue()).isEqualTo("TOKEN_X");
        assertThat(captor.getValue().consumedByTenantId()).isEqualTo("tenant-1");
        assertThat(captor.getValue().consumedAt())
                .isEqualTo(
                        LocalDateTime.ofInstant(
                                Instant.parse("2026-04-27T12:00:00Z"), ZoneOffset.UTC));
    }

    @Test
    void consumeToken_unavailableToken_throwsIllegalStateException() {
        var dao = mock(ConsumedInvitationTokenDao.class);
        when(dao.findAll()).thenReturn(List.of());

        var pool =
                new DefaultInvitationTokenPool(propsWithTokens("TOKEN_Y"), dao, Clock.systemUTC());
        pool.initialize();
        // Consume TOKEN_Y first
        pool.consumeToken("TOKEN_Y", "tenant-1");

        // Second attempt should throw
        assertThatThrownBy(() -> pool.consumeToken("TOKEN_Y", "tenant-2"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void allConsumedFromConfig_poolIsEmpty() {
        var dao = mock(ConsumedInvitationTokenDao.class);
        when(dao.findAll())
                .thenReturn(
                        List.of(
                                new ConsumedInvitationTokenRecord("T1", LocalDateTime.now(), null),
                                new ConsumedInvitationTokenRecord(
                                        "T2", LocalDateTime.now(), null)));

        var pool =
                new DefaultInvitationTokenPool(propsWithTokens("T1", "T2"), dao, Clock.systemUTC());
        pool.initialize();

        assertThat(pool.isAvailable("T1")).isFalse();
        assertThat(pool.isAvailable("T2")).isFalse();
    }
}
