package de.vvwt.slotopt.dispatcher.result;

import java.util.List;
import java.util.UUID;
import org.springframework.data.repository.CrudRepository;

/**
 * Spring Data JDBC repository for {@link LateResult}.
 *
 * <p>Per DEC-35: Spring Data {@code CrudRepository} interfaces ARE the port by definition. No
 * separate public interface wrapper is needed (Spring-Data carve-out).
 *
 * <p>Custom finder: {@link #findByPacketId(UUID)} for forensic lookup of all late results for a
 * given packet.
 *
 * <p>Story: E37S09; AC-LATE-RESULT-REPOSITORY; DEC-35
 */
public interface LateResultRepository extends CrudRepository<LateResult, Long> {

    /**
     * Finds all late results for the given packet UUID.
     *
     * @param packetId the external packet UUID
     * @return list of late results for the packet (may be empty)
     */
    List<LateResult> findByPacketId(UUID packetId);
}
