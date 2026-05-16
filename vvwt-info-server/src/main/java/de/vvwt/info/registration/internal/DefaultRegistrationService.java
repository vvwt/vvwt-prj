// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
package de.vvwt.info.registration.internal;

import de.vvwt.info.dto.registration.AlgorithmDescriptor;
import de.vvwt.info.dto.registration.AlgorithmWarning;
import de.vvwt.info.dto.registration.RegistrationRequest;
import de.vvwt.info.dto.registration.RegistrationResponse;
import de.vvwt.info.persistence.algorithm.AlgorithmRegistryDao;
import de.vvwt.info.persistence.algorithm.AlgorithmRegistryRecord;
import de.vvwt.info.persistence.audit.AuditLogDao;
import de.vvwt.info.persistence.audit.AuditLogRecord;
import de.vvwt.info.persistence.audit.RejectionReason;
import de.vvwt.info.persistence.audit.SignatureOutcome;
import de.vvwt.info.persistence.tenant.TenantDao;
import de.vvwt.info.persistence.tenant.TenantRecord;
import de.vvwt.info.registration.InvitationTokenPool;
import de.vvwt.info.registration.RegistrationService;
import de.vvwt.info.registration.config.RegistrationProperties;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import org.springframework.transaction.annotation.Transactional;

/**
 * Core service for tenant-registration logic (AC5, AC8–AC14).
 *
 * <p>Orchestrates all acceptance-criterion paths for the registration endpoint:
 *
 * <ul>
 *   <li>{@link #listAlgorithms()} — GET /api/v1/register/algorithms (AC5)
 *   <li>{@link #register(String, RegistrationRequest, String, String)} — POST /api/v1/register
 *       (AC5)
 * </ul>
 *
 * <p>5xx-durability for audit-log (AC13): the audit write uses {@code Propagation.REQUIRES_NEW} so
 * it commits even if the outer transaction rolls back.
 *
 * @see <a href="../../../../../../../../docs/governance/stories/E38S04.story.md">E38S04 AC5,
 *     AC8–AC14</a>
 * @see <a href="../../../../../../../../docs/governance/decisions/DEC-42.md">DEC-42 D3</a>
 * @see <a href="../../../../../../../../docs/governance/decisions/DEC-48.md">DEC-48</a>
 */
public class DefaultRegistrationService implements RegistrationService {

    // -------------------------------------------------------------------------
    // Dependencies
    // -------------------------------------------------------------------------

    private final AlgorithmRegistryDao algorithmRegistryDao;
    private final TenantDao tenantDao;
    private final AuditLogDao auditLogDao;
    private final InvitationTokenPool tokenPool;
    private final RegistrationProperties props;
    private final Clock clock;

    public DefaultRegistrationService(
            AlgorithmRegistryDao algorithmRegistryDao,
            TenantDao tenantDao,
            AuditLogDao auditLogDao,
            InvitationTokenPool tokenPool,
            RegistrationProperties props,
            Clock clock) {
        this.algorithmRegistryDao = algorithmRegistryDao;
        this.tenantDao = tenantDao;
        this.auditLogDao = auditLogDao;
        this.tokenPool = tokenPool;
        this.props = props;
        this.clock = clock;
    }

    // -------------------------------------------------------------------------
    // GET /api/v1/register/algorithms — AC5
    // -------------------------------------------------------------------------

    /**
     * Returns all active algorithms from the registry, ordered by {@code algorithm_id} (AC5).
     *
     * @return ordered list of {@link AlgorithmDescriptor} for active algorithms
     */
    @Override
    public List<AlgorithmDescriptor> listAlgorithms() {
        var all = new ArrayList<AlgorithmRegistryRecord>();
        algorithmRegistryDao.findAll().forEach(all::add);
        return all.stream()
                .filter(AlgorithmRegistryRecord::active)
                .sorted(Comparator.comparing(AlgorithmRegistryRecord::algorithmId))
                .map(
                        r ->
                                new AlgorithmDescriptor(
                                        r.algorithmId(),
                                        r.displayName(),
                                        r.deprecationDate(),
                                        null))
                .toList();
    }

    // -------------------------------------------------------------------------
    // POST /api/v1/register — AC5, AC8–AC14
    // -------------------------------------------------------------------------

    /**
     * Processes a tenant registration request.
     *
     * @param tenantId the tenant identifier from the request context (path variable or header)
     * @param request the registration request body
     * @param sourceIp client IP address for audit log
     * @param requestId optional correlation ID for audit log
     * @return {@link RegistrationResponse} on success, or a typed rejection exception instance
     */
    @Override
    @Transactional
    public Object register(
            String tenantId, RegistrationRequest request, String sourceIp, String requestId) {

        RejectionReason rejectionReason = null;
        int httpStatus = 200;

        try {
            // Step 1: Algorithm unknown check
            Optional<AlgorithmRegistryRecord> algoOpt =
                    algorithmRegistryDao.findById(request.algorithm_id());
            if (algoOpt.isEmpty()) {
                rejectionReason = RejectionReason.ALGORITHM_UNKNOWN;
                httpStatus = 400;
                return new AlgorithmUnknownException(request.algorithm_id());
            }

            AlgorithmRegistryRecord algo = algoOpt.get();

            // Step 2: Algorithm deprecation check (DEC-48 boundary)
            if (isDeprecated(algo.deprecationDate())) {
                rejectionReason = RejectionReason.ALGORITHM_DEPRECATED;
                httpStatus = 410;
                return new AlgorithmDeprecatedException(request.algorithm_id());
            }

            // Step 3: Primary profile invitation token check (AC10)
            if ("INVITATION_ONLY".equals(props.getRegistration().getMode())) {
                String token = request.invitation_token();
                if (!tokenPool.isAvailable(token)) {
                    rejectionReason = RejectionReason.INVITATION_INVALID;
                    httpStatus = 403;
                    return new InvitationInvalidException();
                }
            }

            // Step 4: Existing tenant check — first-key-wins (AC8)
            byte[] requestKey = decodePublicKey(request.public_key());
            Optional<TenantRecord> existingTenant = tenantDao.findById(tenantId);
            if (existingTenant.isPresent()) {
                byte[] existingKey = existingTenant.get().publicKey();
                if (!Arrays.equals(existingKey, requestKey)) {
                    rejectionReason = RejectionReason.KEY_MISMATCH;
                    httpStatus = 409;
                    return new KeyMismatchException(tenantId);
                }
                // Same key, same tenant — idempotent re-registration: return success
                return buildResponse(algo);
            }

            // Step 5: Self-host max-tenants check (AC9)
            int maxTenants = props.getTenant().getMaxTenants();
            if (maxTenants > 0 && tenantDao.count() >= maxTenants) {
                rejectionReason = RejectionReason.TENANT_LIMIT_EXCEEDED;
                httpStatus = 403;
                return new TenantLimitExceededException();
            }

            // Step 6: All guards passed — insert tenant row
            boolean isDefault = (tenantDao.count() == 0); // first tenant is default in self-host
            var tenantRecord =
                    new TenantRecord(
                            tenantId,
                            requestKey,
                            request.algorithm_id(),
                            LocalDateTime.now(clock),
                            "ACTIVE",
                            isDefault);
            tenantDao.save(tenantRecord);

            // Step 7: Consume invitation token atomically if primary mode (AC10)
            if ("INVITATION_ONLY".equals(props.getRegistration().getMode())) {
                tokenPool.consumeToken(request.invitation_token(), tenantId);
            }

            return buildResponse(algo);

        } finally {
            // AC13: Audit-log every request (accepted or rejected). REQUIRES_NEW ensures
            // the audit write commits even if the outer transaction rolls back (5xx-durability).
            writeAuditLog(tenantId, sourceIp, requestId, httpStatus, rejectionReason);
        }
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /**
     * DEC-48 deprecation boundary: accepted if {@code
     * Instant.now().isBefore(deprecationDate.plusDays(1).atStartOfDay(UTC).toInstant())}
     */
    private boolean isDeprecated(LocalDate deprecationDate) {
        if (deprecationDate == null) {
            return false;
        }
        Instant boundary = deprecationDate.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant();
        return !Instant.now(clock).isBefore(boundary);
    }

    private RegistrationResponse buildResponse(AlgorithmRegistryRecord algo) {
        AlgorithmWarning warning = null;
        if (algo.deprecationDate() != null) {
            LocalDate today = LocalDate.now(clock);
            long daysRemaining = ChronoUnit.DAYS.between(today, algo.deprecationDate());
            if (daysRemaining >= 0) {
                warning =
                        new AlgorithmWarning(
                                algo.algorithmId(), algo.deprecationDate(), (int) daysRemaining);
            }
        }
        return new RegistrationResponse(null, warning); // tournament_token is E38S05 scope
    }

    private byte[] decodePublicKey(String base64Key) {
        try {
            return Base64.getDecoder().decode(base64Key);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(
                    "Invalid base64 in public_key: " + e.getMessage(), e);
        }
    }

    private void writeAuditLog(
            String tenantId,
            String sourceIp,
            String requestId,
            int httpStatus,
            RejectionReason rejectionReason) {
        var record =
                new AuditLogRecord(
                        null, // id: auto-generated
                        requestId,
                        sourceIp != null ? sourceIp : "unknown",
                        LocalDateTime.now(clock),
                        SignatureOutcome.NA, // first-registration is unsigned per D-X4(b)
                        rejectionReason,
                        httpStatus,
                        tenantId,
                        null, // tournament_id: N/A for registration
                        "/api/v1/register");
        auditLogDao.append(record);
    }
}
