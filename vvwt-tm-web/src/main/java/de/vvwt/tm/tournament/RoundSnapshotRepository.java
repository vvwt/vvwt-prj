package de.vvwt.tm.tournament;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Boundary-API tenant-scoped repository for {@link RoundSnapshot} entities.
 *
 * <p>DEC-58 Clause A + DEC-72: every self-created Spring component must have a public interface in
 * the bounded-context root package.
 *
 * @see RoundSnapshot
 * @since E57S01
 */
public interface RoundSnapshotRepository {

    /**
     * Saves a {@link RoundSnapshot}.
     *
     * @param snapshot the snapshot to save
     * @return the saved snapshot
     */
    RoundSnapshot save(RoundSnapshot snapshot);

    /**
     * Returns the snapshot with the given id.
     *
     * @param id the snapshot UUID
     * @return Optional containing the snapshot if found, empty otherwise
     */
    Optional<RoundSnapshot> findById(UUID id);

    /**
     * Returns all snapshots.
     *
     * @return list of snapshots; never null
     */
    List<RoundSnapshot> findAll();

    /**
     * Deletes the snapshot with the given id.
     *
     * @param id the snapshot UUID
     */
    void deleteById(UUID id);
}
