package de.vvwt.tm.slotopt;

import java.time.LocalDate;

/**
 * Spring {@code ApplicationEvent} published when the dispatcher registration response includes a
 * non-null {@code deprecation_date} for the registered algorithm AND the algorithm is still
 * accepted per DEC-48 boundary semantics (i.e., before the first instant of the day AFTER {@code
 * deprecation_date}).
 *
 * <h2>DEC-43 D3 + DEC-48 compliance</h2>
 *
 * <p>This event satisfies DEC-43 D3's "clear admin warning" obligation for the TM ↔ dispatcher
 * integration point. The exact warning channel ("Spring ApplicationEvent") is
 * implementation-specific per DEC-43 D3's carve-out ("exact warning channel is
 * implementation-specific"). The observable admin-UI rendering (banner endpoint + Svelte component)
 * is a Phase-2+ companion concern per AC-DEC43-D3-NO-ADMIN-UI-CONSUMER-IN-S03.
 *
 * <h2>V1 never-fires invariant</h2>
 *
 * <p>At V1 ship time (DEC-43 D4), the dispatcher announces only {@code "Ed25519"} with {@code null}
 * deprecation_date. This event is therefore never published at V1 — it is defensive code for
 * Phase-2+ algorithm migration (ML-DSA, SLH-DSA) per Brief S-3a. The null-deprecation-date fast
 * path is short-circuited in {@code DefaultSlotOptimizationDispatcherClient}; see documentation
 * comment there.
 *
 * @param algorithmId the server-canonical algorithm identifier (e.g., {@code "Ed25519"})
 * @param displayName the human-readable display name
 * @param deprecationDate the date after which (exclusive, per DEC-48) the algorithm is no longer
 *     accepted for new registrations; never null when this event is published
 * @param recommendedMigrationTarget the first non-deprecated algorithm from the server's announced
 *     list, or {@code null} if no non-deprecated alternative was announced
 * @see <a href="../../../../../../../../docs/governance/decisions/DEC-43.md">DEC-43 D3</a>
 * @see <a href="../../../../../../../../docs/governance/decisions/DEC-48.md">DEC-48</a>
 * @see <a href="../../../../../../../../docs/governance/stories/E27S03.story.md">Story E27S03</a>
 */
public record SlotOptimizationDeprecationWarningEvent(
        String algorithmId,
        String displayName,
        LocalDate deprecationDate,
        String recommendedMigrationTarget) {}
