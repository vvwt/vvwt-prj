package de.vvwt.tm.tournament.internal;

import de.vvwt.tm.tournament.TeamAvatar;
import java.util.UUID;
import org.springframework.data.repository.CrudRepository;

/**
 * Spring Data JDBC CrudRepository for {@link TeamAvatar} — internal to the tournament module
 * (DEC-21).
 *
 * <p>Used exclusively by {@code TeamAvatarRepository} for Spring Data query delegation. External
 * code must only access {@code TeamAvatarRepository} (the public API surface per DEC-21 §Module
 * layout).
 *
 * <p>Inventory line 456 ({@code TeamAvatarRepository}).
 *
 * @see de.vvwt.tm.tournament.TeamAvatarRepository
 * @see <a href="DEC-21">DEC-21 — Spring Modulith, internal package = implementation</a>
 * @see <a href="E21S04">E21S04 — Team aggregate reconstruction</a>
 */
public interface TeamAvatarCrudRepository extends CrudRepository<TeamAvatar, UUID> {}
