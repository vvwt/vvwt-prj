package de.vvwt.slotopt.dispatcher.result;

import java.util.List;
import java.util.UUID;
import org.springframework.data.repository.CrudRepository;

/**
 * Spring Data JDBC repository for {@link ResultAuditEntry}.
 *
 * <p>Per DEC-35: Spring Data {@code CrudRepository} interface IS the port. No separate interface
 * wrapper needed (Spring-Data carve-out).
 *
 * <p>Story: E37S09; AC-RESULT-AUDIT; DEC-35
 */
public interface ResultAuditRepository extends CrudRepository<ResultAuditEntry, Long> {

    /**
     * Finds all audit entries for the given packet UUID.
     *
     * @param packetId the packet UUID
     * @return list of audit entries (may be empty)
     */
    List<ResultAuditEntry> findByPacketId(UUID packetId);
}
