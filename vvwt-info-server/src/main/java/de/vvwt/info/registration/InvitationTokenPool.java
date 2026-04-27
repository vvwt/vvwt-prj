package de.vvwt.info.registration;

import de.vvwt.info.persistence.invitation.ConsumedInvitationTokenDao;
import de.vvwt.info.persistence.invitation.ConsumedInvitationTokenRecord;
import de.vvwt.info.registration.config.RegistrationProperties;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.StreamSupport;
import org.springframework.transaction.annotation.Transactional;

/**
 * In-memory invitation-token pool for the primary registration profile (AC10).
 *
 * <p>On startup (via {@link #initialize()}), the pool is built as:
 *
 * <pre>operator-config-list MINUS consumed_invitation_tokens DB contents</pre>
 *
 * This reconciliation ensures that tokens consumed before a server restart are not re-issued.
 *
 * <p>Token consumption is atomic: the token is removed from the in-memory pool AND inserted into
 * {@code consumed_invitation_tokens} in a single transaction (via {@link #consumeToken}).
 *
 * @see <a href="../../../../../../../docs/governance/stories/E38S04.story.md">E38S04 AC10</a>
 * @see <a href="../../../../../../../docs/governance/decisions/DEC-42.md">DEC-42 D3</a>
 */
public class InvitationTokenPool {

    private final List<String> configTokens;
    private final ConsumedInvitationTokenDao consumedTokenDao;
    private final Clock clock;

    /** The live pool: config tokens minus already-consumed tokens. */
    private final Set<String> availableTokens = Collections.synchronizedSet(new HashSet<>());

    /**
     * Constructs the pool. Call {@link #initialize()} to populate after construction (typically at
     * Spring context startup via {@code @PostConstruct} or explicit call in config).
     *
     * @param properties registration properties (carries the invitation-tokens list)
     * @param consumedTokenDao DAO for persisted consumed tokens
     * @param clock injectable clock for testability (AC4 pattern — same Clock injection strategy)
     */
    public InvitationTokenPool(
            RegistrationProperties properties,
            ConsumedInvitationTokenDao consumedTokenDao,
            Clock clock) {
        this.configTokens = List.copyOf(properties.getInvitationTokens());
        this.consumedTokenDao = consumedTokenDao;
        this.clock = clock;
    }

    /**
     * Initializes the pool by loading consumed tokens from the DB and computing the available set.
     *
     * <p>Must be called once after construction (typically {@code @PostConstruct}).
     */
    public void initialize() {
        Set<String> consumed = new HashSet<>();
        StreamSupport.stream(consumedTokenDao.findAll().spliterator(), false)
                .map(ConsumedInvitationTokenRecord::tokenValue)
                .forEach(consumed::add);

        availableTokens.clear();
        for (String token : configTokens) {
            if (!consumed.contains(token)) {
                availableTokens.add(token);
            }
        }
    }

    /**
     * Returns {@code true} if the given token is currently in the available pool.
     *
     * @param token the token to check
     * @return {@code true} if available; {@code false} if already consumed or unknown
     */
    public boolean isAvailable(String token) {
        return token != null && availableTokens.contains(token);
    }

    /**
     * Atomically consumes a token: removes from in-memory pool and persists to DB.
     *
     * <p>This method must be called within a Spring transaction so that the in-memory removal and
     * the DB insert are atomic (Spring `@Transactional` on the calling service method ensures
     * this).
     *
     * @param token the token to consume
     * @param tenantId the tenant that consumed the token (for auditability)
     * @throws IllegalStateException if the token is not in the available pool (caller must check
     *     {@link #isAvailable} first within the same transaction)
     */
    @Transactional
    public void consumeToken(String token, String tenantId) {
        if (!availableTokens.remove(token)) {
            throw new IllegalStateException(
                    "Attempted to consume invitation token that is not available: " + token);
        }
        var record = new ConsumedInvitationTokenRecord(token, LocalDateTime.now(clock), tenantId);
        consumedTokenDao.save(record);
    }
}
