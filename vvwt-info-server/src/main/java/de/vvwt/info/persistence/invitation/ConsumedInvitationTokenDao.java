package de.vvwt.info.persistence.invitation;

import org.springframework.data.repository.CrudRepository;

/**
 * Spring Data JDBC repository for {@link ConsumedInvitationTokenRecord}.
 *
 * <p>Tracks consumed invitation tokens for the primary-profile registration flow (AC10). The {@code
 * token_value} PK enables O(1) existence checks during token validation.
 *
 * <p>Consumption is atomic: the service removes the token from the in-memory pool AND inserts a row
 * here in a single transaction. On restart, the in-memory pool is rebuilt as:
 *
 * <pre>operator-config-list MINUS {@link #findAll()} contents</pre>
 *
 * @see <a href="../../../../../../../docs/governance/stories/E38S04.story.md">E38S04 AC10</a>
 * @see <a href="../../../../../../../docs/governance/decisions/DEC-42.md">DEC-42 D3</a>
 * @see <a href="../../../../../../../docs/governance/decisions/DEC-46.md">DEC-46</a>
 */
public interface ConsumedInvitationTokenDao
        extends CrudRepository<ConsumedInvitationTokenRecord, String> {}
