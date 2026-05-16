// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
package de.vvwt.info.persistence.audit;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * RED-first test verifying that {@link AuditLogDao} exposes NO {@code update*}, {@code delete*}, or
 * {@code remove*} methods (AC10 — structural append-only enforcement).
 *
 * <p>This test uses reflection to enumerate all public methods on {@code AuditLogDao} and asserts
 * that none match the forbidden naming patterns. AC10 requires structural enforcement: even if
 * Spring Data JDBC's {@code CrudRepository} base interface exposes {@code delete*} methods, the
 * facade DAO must NOT expose them to consumers.
 *
 * <p>DEC-22 Iron Law: this test was written RED-first before {@code AuditLogDao} existed.
 *
 * @see <a href="../../../../../../../docs/governance/stories/E38S03.story.md">E38S03 AC10</a>
 */
class AuditLogDaoMethodNamesTest {

    private static final Pattern FORBIDDEN_PATTERN =
            Pattern.compile("^(update|delete|remove).*", Pattern.CASE_INSENSITIVE);

    @Test
    void auditLogDao_exposes_no_mutation_methods() {
        List<Method> forbiddenMethods =
                Arrays.stream(AuditLogDao.class.getMethods())
                        .filter(m -> FORBIDDEN_PATTERN.matcher(m.getName()).matches())
                        .toList();

        assertThat(forbiddenMethods)
                .as(
                        "AuditLogDao must not expose update*/delete*/remove* methods"
                                + " (AC10 append-only enforcement). Found: %s",
                        forbiddenMethods.stream().map(Method::getName).toList())
                .isEmpty();
    }

    @Test
    void auditLogDao_exposes_append_method() {
        long appendCount =
                Arrays.stream(AuditLogDao.class.getMethods())
                        .filter(m -> m.getName().equals("append"))
                        .count();
        assertThat(appendCount)
                .as("AuditLogDao must expose an 'append' method (AC10)")
                .isGreaterThanOrEqualTo(1);
    }
}
