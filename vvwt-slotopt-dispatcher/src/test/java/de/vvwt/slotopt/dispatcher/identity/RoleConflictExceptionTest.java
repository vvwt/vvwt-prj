// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
package de.vvwt.slotopt.dispatcher.identity;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link RoleConflictException}.
 *
 * <p>RED-first per DEC-22 / AC-ROLE-CONFLICT-EXCEPTION: written before production class exists.
 *
 * <p>Story: E37S05
 */
class RoleConflictExceptionTest {

    @Test
    void storesWorkerIdExistingRoleAndRequestedRole() {
        UUID workerId = UUID.randomUUID();
        String existingRole = "worker";
        String requestedRole = "submitter";

        RoleConflictException ex = new RoleConflictException(workerId, existingRole, requestedRole);

        assertThat(ex.getWorkerId()).isEqualTo(workerId);
        assertThat(ex.getExistingRole()).isEqualTo(existingRole);
        assertThat(ex.getRequestedRole()).isEqualTo(requestedRole);
    }

    @Test
    void messageContainsWorkerIdAndBothRoles() {
        UUID workerId = UUID.fromString("11111111-1111-1111-1111-111111111111");
        RoleConflictException ex = new RoleConflictException(workerId, "worker", "submitter");

        String message = ex.getMessage();
        assertThat(message).contains(workerId.toString());
        assertThat(message).contains("worker");
        assertThat(message).contains("submitter");
    }

    @Test
    void isRuntimeException() {
        RoleConflictException ex =
                new RoleConflictException(UUID.randomUUID(), "worker", "submitter");
        assertThat(ex).isInstanceOf(RuntimeException.class);
    }
}
