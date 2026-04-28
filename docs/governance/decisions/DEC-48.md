<!-- Snapshot of outer-repo .gaai/project/contexts/memory/decisions/DEC-48.md at 225e1a832e45b1c153f2bb97ca38edf847b0762f 2026-04-28 -->
---
id: DEC-48
domain: architecture
level: architectural
title: "Amendment to DEC-43 D3: UTC-end-of-day deprecation boundary formalized as 'first instant of the day AFTER `deprecation_date`' (i.e., `< deprecation_date.plusDays(1).atStartOfDay(UTC).toInstant()` is accepted; at-or-after is rejected) — corrects 1-second semantic ambiguity in DEC-43 D3 literal text"
status: active
created_by: discovery
created_at: 2026-04-27
last_updated_by: discovery
last_updated_at: 2026-04-27
supersedes: null
superseded_by: null
amends: DEC-43
tags:
  - cryptography
  - algorithm-agility
  - boundary-semantics
  - deprecation-date
  - amendment
  - utc-end-of-day
related_to: [DEC-6, DEC-43]
session_brief_ref: discovery-2026-04-26-e38-public-info-portal-phase1
---

# DEC-48 — Amendment to DEC-43 D3: UTC-end-of-day deprecation boundary semantics

## Context

DEC-43 D3 (2026-04-26, post-cycle-2 fix) specifies the deprecation-date boundary for asymmetric-key algorithm registration. Its **Boundary semantics** paragraph reads:

> "`deprecation_date` is interpreted as **UTC end-of-day**. A new registration submitted at any instant `≥ deprecation_date.atTime(23:59:59Z)` is rejected. Clock-skew tolerance (e.g., a 60s grace) is a Phase 1 implementation detail and not DEC-43-prescribed; the server clock is authoritative."

The literal text picks **`23:59:59Z`** (the LAST second of the deprecation day) as the rejection trigger. Per DEC-43 D3 literal, a registration arriving at exactly `2026-12-31T23:59:59Z` for a `deprecation_date = 2026-12-31` is **REJECTED** — the deprecation day's last second is itself the threshold. The last accepted instant is `23:59:58Z`.

E38S04 cycle-1 review (Brief `discovery-2026-04-26-e38-public-info-portal-phase1`) flagged a multi-formulation contradiction across AC4/AC11/Notes (cycle-1 F4/F5). The cycle-1 fix attempted to unify the formulation by pinning `Instant.now().isBefore(deprecation_date.plusDays(1).atStartOfDay(UTC).toInstant())` as the acceptance predicate — a 1-second-shifted semantic where `2026-12-31T23:59:59Z` is **ACCEPTED** and `2027-01-01T00:00:00Z` is the rejection trigger. Cycle-2 review (F-R1, HIGH) caught this 1-second contradiction between AC4 and the DEC-43 D3 literal text.

The human (Path B selection, 2026-04-27) chose to **amend DEC-43 D3** to formalize the cycle-1-fix semantics as authoritative — rather than realign the story to DEC-43 D3's literal `23:59:59Z` reject trigger. Rationale: the amendment-formalized semantics (deprecation_date day ENTIRELY accepted; rejection starts next day) is more intuitive — "deprecation_date is the last day the algorithm is accepted" matches operator/admin mental model better than "deprecation_date is the day the algorithm is partially rejected starting at 23:59:59Z."

This amendment follows the DEC-34/DEC-36/DEC-41/DEC-46 delta-override pattern: DEC-43's clauses D1/D2/D4 remain textually unchanged; the D3 boundary-semantics paragraph is replaced.

## Decision

DEC-43 D3's **Boundary semantics** paragraph is replaced with the following text:

> "`deprecation_date` is interpreted as **UTC end-of-day**, formally defined as **the first instant of the day AFTER `deprecation_date` in UTC**. A new registration is accepted if and only if `Instant.now().isBefore(deprecation_date.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant())`; otherwise it is rejected. Equivalently: the entire deprecation_date day (from `00:00:00Z` through `23:59:59.999999999Z`) is the LAST day the algorithm is accepted; rejection starts at the first instant of the day after `deprecation_date`. Clock-skew tolerance (e.g., a 60s grace) is a Phase 1 implementation detail and not DEC-43-prescribed; the server clock is authoritative."

### Concrete examples (for clarity)

Given `deprecation_date = 2026-12-31`:

| Registration submitted at instant (UTC) | Outcome |
|---|---|
| `2026-12-31T00:00:00Z` | ACCEPTED |
| `2026-12-31T12:00:00Z` | ACCEPTED |
| `2026-12-31T23:59:58Z` | ACCEPTED |
| `2026-12-31T23:59:59Z` | **ACCEPTED** (was REJECTED under DEC-43 D3 literal) |
| `2026-12-31T23:59:59.999Z` | ACCEPTED |
| `2027-01-01T00:00:00Z` | REJECTED |
| `2027-01-01T00:00:01Z` | REJECTED |

The 1-second window `[2026-12-31T23:59:59Z, 2027-01-01T00:00:00Z)` shifts from "rejected" (DEC-43 D3 literal) to "accepted" (DEC-48 amended).

### Explicit changes to DEC-43

- **DEC-43 D3 Boundary-semantics paragraph is replaced** by the text above. All other text in DEC-43 D3 (deprecation-warning surface, registration-error response semantics, pre-existing-registration verification continuity) remains unchanged.
- **DEC-43 D1/D2/D4 are unchanged.**
- **DEC-43 frontmatter updates (minimal):** `last_updated_at` advances to `2026-04-27`; `amended_by` field appends `DEC-48`. No other frontmatter changes.

### What this amendment does NOT change in DEC-43

- **The protocol shape** — server announces algorithm list with optional deprecation-date per algorithm (D1) is unchanged.
- **Client-chosen-algorithm semantics** (D2) — pre-deprecation client free-choice; post-deprecation new-registration rejection — is unchanged at the policy level; only the boundary instant is shifted by 1 second.
- **Pre-existing-registration verification continuity** (D3 paragraph 1) — signature verifications for pre-existing registrations using a now-deprecated algorithm continue to succeed — is unchanged.
- **V1 algorithm set** (D4) — Ed25519 only, ML-DSA / SLH-DSA migration under same protocol shape — is unchanged.
- **DEC-6 foundation** — asymmetric-key registration — unchanged.

## Impact

- **DEC-43 D3 Boundary-semantics paragraph textually replaced** by the DEC-48 wording. Pointer paragraph appended at DEC-43's tail; `amended_by: [DEC-48]`.
- **Epic E38 stories** that pin the boundary semantics — specifically **E38S04 AC4** — cite DEC-48 as the boundary-semantics authority. E38S04's cycle-1-F4/F5 fix that established `< deprecation_date.plusDays(1).atStartOfDay(UTC).toInstant()` is now correct against the authoritative DEC.
- **TM-side publisher (E38S09)** reads the same `deprecation_date` field from the `RegistrationResponse.algorithm_warning`; the human-facing "days remaining" calculation is unchanged in its arithmetic but now formally correct (operator sees "X days remaining until END-OF-DAY of deprecation_date" matching the new semantics).
- **No code or test change required in DEC-43-pre-existing artefacts** — DEC-43 was authored 2026-04-26, no production code yet exists that consumes the original literal `23:59:59Z` boundary. The amendment lands before any Delivery cycle exercises the boundary.
- **In-flight `vvwt-slotopt-dispatcher` work (Epic E37)** — when E37 algorithm-agility scope is delivered (its DAO IT and signature-verification stories), it inherits DEC-48 via the same DEC-43 amendment. Forward-advisory.
- **Future PQC migrations (ML-DSA, SLH-DSA, Falcon)** under the same DEC-43 protocol shape adopt the DEC-48-amended boundary automatically; no further DEC required per algorithm addition.
- **`patterns/conventions.md`** is unchanged at this DEC's commit. If a future operationalization story propagates DEC-43 protocol-shape conventions, it cites DEC-48 for the boundary semantics.

## Alternatives ruled out

- **Story-realign-to-DEC-43-literal (Path A)** — edit E38S04 AC4 to match DEC-43 D3 literal `≥ 23:59:59Z` rejection; no DEC churn. Rejected by the human in favor of cleaner semantics. Path A would have produced a 1-second-narrower acceptance window with a counterintuitive boundary (the LAST second of the deprecation day is itself the reject trigger).
- **Inline edit of DEC-43 D3 text** (rewrite the boundary paragraph in-place). Rejected: DEC-34/DEC-36/DEC-41/DEC-46 established the delta-override pattern (preserve original DEC text; amendment via separate DEC). DEC-48 follows that precedent.
- **Author the boundary-semantics in `patterns/conventions.md`** and remove from DEC-43. Rejected: the boundary is a protocol-shape decision, not a coding convention. DEC-43 + DEC-48 are the authoritative governance pair; conventions.md is for repeating-coding-pattern documentation.
- **Different boundary alternatives** — e.g., end-of-day at `23:59:59.999Z` (millisecond-precision), or end-of-day at server-local-tz midnight. Rejected: DEC-43 D3 was UTC-authoritative, and the 1-second shift to first-instant-of-next-day is the cleanest preservation of UTC-authoritative + day-aligned semantics.

## References

- Session Brief: `discovery-2026-04-26-e38-public-info-portal-phase1` (validated by SUB-AGENT-REVIEW-001 Tier 2 cycles 1+2; Brief v3.1 human-validated 2026-04-26).
- E38S04 cycle-2 review (2026-04-27) raised F-R1 HIGH: "AC4 contradicts DEC-43 D3 literal boundary text by 1 second." Human selected Path (B) — amend DEC-43 — over Path (A) realign-story-to-DEC.
- Related DECs:
  - **DEC-43** — Algorithm-agility extension to DEC-6 (this DEC's textual subject; D3 boundary paragraph amended here).
  - DEC-6 — Asymmetric-key registration foundation (DEC-43's foundation; unchanged).
  - DEC-34 — Amendment pattern precedent (delta-override).
  - DEC-36 — Amendment pattern precedent.
  - DEC-41 — Amendment pattern precedent.
  - DEC-46 — Amendment pattern precedent (DEC-26 multi-module scope-extension; same Discovery session).
- Coordination flag: in-flight Epic E37 (`vvwt-slotopt-dispatcher`) — when E37 algorithm-agility scope is delivered, its boundary tests adopt DEC-48-amended semantics. Forward-advisory.
