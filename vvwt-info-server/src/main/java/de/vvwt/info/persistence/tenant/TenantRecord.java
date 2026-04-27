package de.vvwt.info.persistence.tenant;

import java.time.LocalDateTime;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

/**
 * Spring Data JDBC entity record for the {@code tenant} table.
 *
 * <p>Represents a registered publisher (TM instance) in the PPIS. The {@code is_default} flag
 * distinguishes default-tenant from non-default per DEC-42 D3 (C-B1a/C-B1b).
 *
 * <p>The {@code public_key} column is variable-length binary (VARBINARY(8192) on H2, BYTEA on
 * PostgreSQL) for DEC-43 D4 PQC future-readiness (AC8). Application-layer guard enforces {@code
 * octet_length(public_key) <= 8192} at write time.
 *
 * @param tenantId stable unique tenant identifier — PK
 * @param publicKey raw bytes of the registered public key (DEC-6, DEC-43 D4)
 * @param algorithmId FK → algorithm_registry.algorithm_id (AC9)
 * @param registeredAt UTC timestamp of first registration (first-key-wins per DEC-42 D3)
 * @param status registration status string (e.g., {@code "ACTIVE"})
 * @param isDefault {@code true} for the default tenant; {@code false} for non-default tenants
 * @see <a href="../../../../../../../docs/governance/stories/E38S03.story.md">E38S03</a>
 * @see <a href="../../../../../../../docs/governance/decisions/DEC-42.md">DEC-42</a>
 * @see <a href="../../../../../../../docs/governance/decisions/DEC-43.md">DEC-43</a>
 */
@Table("tenant")
public record TenantRecord(
        @Id @Column("tenant_id") String tenantId,
        @Column("public_key") byte[] publicKey,
        @Column("algorithm_id") String algorithmId,
        @Column("registered_at") LocalDateTime registeredAt,
        @Column("status") String status,
        @Column("is_default") boolean isDefault) {}
