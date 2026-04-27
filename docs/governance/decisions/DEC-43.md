<!-- Snapshot of outer-repo .gaai/project/contexts/memory/decisions/DEC-43.md at cf2ebf6c9b1e6752b7a62413c0b1dab007d58762 2026-04-27 -->
---
id: DEC-43
domain: architecture
level: architectural
title: "Algorithm-agility for asymmetric-key registration — registration handshake announces algorithm list with optional deprecation-date per algorithm; classical primitive (Ed25519) at V1, post-quantum migration path under same protocol shape; applies to both slot-opt and PPIS integration points"
status: active
created_by: discovery
created_at: 2026-04-26
last_updated_by: discovery
last_updated_at: 2026-04-26
supersedes: null
superseded_by: null
tags:
  - cryptography
  - signature-algorithms
  - algorithm-agility
  - pqc
  - post-quantum
  - registration
  - dec-6-extension
  - protocol-shape
related_to: [DEC-6, DEC-42]
session_brief_ref: discovery-2026-04-26-e38-public-info-portal-phase1
---

# DEC-43 — Algorithm-agility extension to DEC-6 registration handshake

## Context

DEC-6 (2026-04-11) established asymmetric-key registration with per-request signature verification at two integration points: (a) the slot-optimization service receiving result packets from volunteer compute clients, and (b) the participant-info service receiving tournament uploads from Tournament Manager instances. DEC-6 specified the security pattern but was deliberately silent on the cryptographic primitive — neither RSA, ECDSA, nor Ed25519 were named.

The Discovery session that produced DEC-42 (`discovery-2026-04-26-e38-public-info-portal-phase1`) surfaced the explicit requirement that the registration protocol be **prepared for a future migration to post-quantum cryptography (PQC)** without a wire-protocol break. Per the planning concretization at `planung/public-information-portal.md` § "Vorbereitung für Umstellung auf Post Quanten Kryptographie (PQC)":

> Bei der Registrierung muss der Public Participant Info Service-Server die verfügbaren Algorithmen an den Client übergeben. Hierbei sollte auch ein Datums-Feld übergeben werden. Wenn dort ein Wert eingetragen ist, dann gilt der Algorithmus als Deprecated und wird nur noch bis zu diesem Datum unterstützt. Der Client muss dann seinem Admin eine deutliche Warnung ausgeben. Vor diesem Datum hat der Client die freie Wahl des Algorithmus.

This is a protocol-shape requirement, not an algorithm choice. It states: the wire protocol must carry algorithm metadata so that the algorithm set is operator-tunable over time, with a deprecation signal that gives clients a deadline-driven incentive to migrate. The same requirement applies to the slot-optimization integration point (DEC-6 (a)) — the worker ↔ dispatcher signing path also needs algorithm-agility, since both NIST-PQC migration timing and operator preference will diverge over the multi-year V1+ horizon.

A parallel-but-incomplete Discovery session today drafted Epic-stage artefacts for `vvwt-slotopt-dispatcher` reconstruction (file system: `E37.epic.md`, stories `E37S01..E37S11`; not yet committed or backlog-registered as of this DEC's creation). That work explicitly plans an algorithm-agility decision for the worker ↔ dispatcher integration point. By drafting DEC-43 as a **single cross-cutting decision** covering both integration points, this Discovery session forecloses the duplication risk: a single algorithm-list-with-deprecation-date protocol shape is canonical; both `vvwt-slotopt-*` (when its Discovery resumes) and `vvwt-info-*` (Epic E38) reference DEC-43.

DEC-6 is silent on algorithm negotiation. DEC-43 fills the silent gap. DEC-6's text is therefore **unchanged**; this DEC complements and extends it without supersession or amendment.

## Decision

DEC-6's registration handshake is extended with an algorithm-list-with-deprecation-date negotiation protocol. The decision has four clauses.

### D1 — Registration handshake announces server's supported algorithms

When a client (compute worker, or Tournament Manager instance) initiates registration with a service (dispatcher, or info-server), the **server's registration response** includes a list of currently-supported signature algorithms. Each algorithm entry carries:

| Field | Type | Required | Meaning |
|---|---|---|---|
| `algorithm_id` | string identifier | YES | Server-canonical identifier for the algorithm (e.g., `ed25519`, `ml-dsa-65`, `slh-dsa-shake-128f`). Stable across protocol versions; new algorithms are additive. |
| `display_name` | string | YES | Human-readable name for operator/admin UIs (e.g., `Ed25519`, `ML-DSA-65 (NIST PQC, 2024)`). |
| `deprecation_date` | ISO-8601 date or `null` | NO | If non-null: the algorithm is considered DEPRECATED and will not be accepted for **new registrations** after this date. **Verification of signatures from pre-existing registrations continues per D3** until a separate Phase 2+ migration story closes the window. If null: the algorithm is supported indefinitely. |
| `parameters` | JSON object or `null` | NO | Algorithm-specific parameter set, if the algorithm has parameter variants (e.g., curve, key size). Null for parameterless algorithms. |

The handshake's response shape is **additive**: clients ignore unknown fields, allowing new metadata (e.g., performance hints) to be added in MINOR schema versions without breaking older clients (DEC-21 forward-compat semantics for envelopes apply).

### D2 — Client chooses freely from non-deprecated algorithms

Before the deprecation date (or unconditionally if `deprecation_date == null`), the client has **free choice** of any algorithm from the server's announced list. The client submits its registration with:
- the public key it generated using the chosen algorithm
- the chosen `algorithm_id` as an explicit field on the registration request

The server validates: (a) `algorithm_id` is in its current supported list, (b) the algorithm is not past its deprecation date (server clock authoritative), (c) the public key is well-formed for the named algorithm. On success, the registration is permanent (DEC-6 + DEC-42 D3 first-key-wins binding).

### D3 — Deprecation warning to admin

When a client successfully registers using an algorithm whose `deprecation_date` is non-null and in the future, the client MUST surface a **clear admin warning** locally — not silently — naming the algorithm, the deprecation date, and the recommended migration target (which the client can derive from the announced list or display the full non-deprecated subset). The exact warning channel is implementation-specific to each integration point (TM admin UI surface for `vvwt-info` registrations; worker log + standalone-worker stderr for slot-opt registrations) — DEC-43 mandates the warning's existence and content, not the channel.

After the deprecation date passes, the server returns a registration error (HTTP 410 Gone or equivalent application-level error) for any **new registration request** using a deprecated algorithm; the client surfaces this error and prompts the operator to re-register with a non-deprecated algorithm. **Signature verifications for pre-existing registrations using a now-deprecated algorithm continue to succeed** until a separate re-registration story (Phase 2+) closes the migration window — DEC-43 does not specify forced rotation. This continuity rule prevents active tournaments / active compute jobs from breaking at deprecation-date crossing.

**Boundary semantics:** `deprecation_date` is interpreted as **UTC end-of-day**. A new registration submitted at any instant `≥ deprecation_date.atTime(23:59:59Z)` is rejected. Clock-skew tolerance (e.g., a 60s grace) is a Phase 1 implementation detail and not DEC-43-prescribed; the server clock is authoritative.

### D4 — V1 algorithm set: classical primitive only

For Epic E37 (`vvwt-slotopt-dispatcher`) and Epic E38 (`vvwt-info`) V1 ship dates, the announced algorithm set is **`Ed25519` only**. Rationale:

- **JDK 21 native support** — no third-party crypto dependency; DEC-3 compliant by minimal-dependency footprint.
- **Open-source standard** — RFC 8032 (EdDSA) is mature, audited, widely deployed.
- **Compact key + signature size** — 32 bytes public key, 64 bytes signature; suits both volunteer-compute (worker→dispatcher) and TM-publisher (TM→info-server) traffic profiles.
- **Performance** — order-of-magnitude faster signing/verification than RSA at equivalent classical security levels.

ML-DSA (NIST FIPS 204) and SLH-DSA (NIST FIPS 205), the NIST-standardized PQC signature algorithms, are **explicitly Phase-2+ scope**. They are added under DEC-43's protocol shape as a future story (per integration point); the wire protocol does not break. JDK native support for ML-DSA / SLH-DSA is expected to land in JDK 25+; until then, addition would require Bouncy Castle (DEC-3 compliant via OSS license but adds dependency surface). Hybrid composite signatures (classical + PQC concatenated) are explicitly out of scope for V1 per the same logic.

Algorithm-set tuning is **operator-configurable** via the server's algorithm registry (DEC-42 D4 schema includes the `algorithm_registry` table for `vvwt-info-server`; the slot-opt dispatcher will adopt an analogous registry per its own Discovery scope). Adding a new algorithm to the registry is a code-only change in V1 (no new DEC required per addition); deprecating an algorithm on a public-facing primary requires operator coordination (out of DEC-43 scope; expected to be Phase 2+ operator playbook).

## Impact

- **DEC-6 textually unchanged.** DEC-6's text is silent on algorithm negotiation; DEC-43 fills the silent gap. DEC-6's `last_updated_at` is NOT advanced; no `amends` field on this DEC. DEC-6's `related_to` is not back-edited; this DEC's `related_to: [DEC-6, ...]` establishes the cross-link forward only.
- **Both integration points are bound by DEC-43 from V1.**
  - `vvwt-info-server` (Epic E38) — registration handshake, signature verification, `algorithm_registry` table all per DEC-43 D1–D4.
  - `vvwt-slotopt-dispatcher` (Epic E37 — currently dangling on disk, not yet backlog-registered) — when E37 is registered or resumed, its algorithm-agility scope (E37S04 in the dangling artefact set) **MUST** reference DEC-43 instead of drafting a parallel decision. Coordination flagged for the human; DEC-43 is the canonical algorithm-agility reference.
- **Epic E38 stories** include AC referencing DEC-43 wherever the registration handshake or signature verification is in scope (bootstrap story, registration story, audit-log story per DEC-42 D4).
- **`patterns/conventions.md` updates** (operationalization story in Epic E38): a new "Cryptography & Algorithm Agility" subsection naming DEC-43 as the canonical reference for asymmetric-key signing across all DEC-6 integration points.
- **Future PQC migration is a code-only path**, not a wire-protocol-version bump. Adding ML-DSA / SLH-DSA / Falcon / hybrid is a registry entry + verifier implementation in the relevant module's crypto package; no DEC-43 amendment required (DEC-43 is the protocol shape, not the algorithm choice).
- **Server clock authority** for deprecation-date evaluation. Standard NTP-time invariants apply at deployment; explicit clock-skew tolerance (e.g., 60s grace) is a Phase 1 implementation detail, not DEC-43-prescribed.
- **No retroactive force-rotation** of pre-existing registrations on deprecation-date passage. DEC-43 D3 specifies that deprecated-algorithm registrations continue to VERIFY until a separate Phase 2+ migration story closes the window. This avoids breaking active tournaments / active compute jobs at deprecation-date crossing.

## Alternatives ruled out

- **Hardcode Ed25519 in the V1 protocol with a future "v2 protocol" migration**. Rejected: forces a wire-protocol-version bump for PQC adoption, breaking forward compatibility. Algorithm-list-with-deprecation-date is the standard industry pattern (TLS cipher suite negotiation, SSH key-exchange algorithms, OpenPGP signature algorithm tables) and avoids the version-bump cost.
- **Deprecation deadline as an absolute server-clock cutoff with hard rotation on the deadline**. Rejected: would break in-flight tournaments / in-flight compute jobs at deadline crossing. DEC-43 D3 deprecation rejects new registrations after deadline but continues to verify pre-existing registrations; explicit migration is a separate Phase 2+ story.
- **Two separate DECs**, one for slot-opt algorithm-agility and one for PPIS algorithm-agility. Rejected: protocol shape is identical; scope distinction is the integration point's algorithm SET (which can differ via `algorithm_registry` configuration), not the negotiation mechanic. A single cross-cutting DEC avoids drift between the two integration points and forecloses parallel-DEC duplication noted in §Context.
- **PQC algorithms in the V1 announced set (ML-DSA-65 alongside Ed25519)**. Rejected: V1 ships before JDK native support; would force a Bouncy Castle dependency at V1 with no operational benefit (no PQC threat at V1 horizons; classical Ed25519 covers V1's threat model). Phase-2+ addition under the same protocol is the deferred path.
- **Hybrid composite signatures at V1** (classical + PQC concatenated). Rejected: doubles the signature size and verification cost without V1 threat justification. Industry consensus on composite signature schemes is also not yet stabilized as of 2026-04 (NIST hybrid drafts in flux).
- **Per-message algorithm negotiation** (each request announces its algorithm preference). Rejected: redundant — registration binds the client to a single algorithm + key per the first-key-wins rule (DEC-6 + DEC-42 D3). Per-message negotiation would conflict with the permanent-binding semantics.
- **Client-driven algorithm proposal** (client offers, server accepts/rejects). Rejected: server controls its supported algorithm set as an operational policy concern (which crypto libraries are vetted, performance budgets, deprecation timing). Server-announces / client-chooses is the inverse direction with cleaner operator control.

## References

- Session Brief: `discovery-2026-04-26-e38-public-info-portal-phase1` (validated by SUB-AGENT-REVIEW-001 Tier 2 cycles 1+2; D-24 PQC future-readiness item refined per cycle-1 finding F-1, escalation reframing to "extends DEC-6 protocol shape" per cycle-2 finding F1).
- Planning concretization: `planung/public-information-portal.md` § "Vorbereitung für Umstellung auf Post Quanten Kryptographie (PQC)".
- Related DECs:
  - DEC-6 — asymmetric-key registration (this DEC's foundation; textually unchanged).
  - DEC-42 — Public Participant Info Service architectural foundation (this DEC's primary application site for the TM ↔ info-server integration; companion DEC from the same Discovery session).
- Coordination flag: existing dangling `E37.epic.md` + `E37S01..E37S11.story.md` artefacts on disk (not yet backlog-registered as of 2026-04-26) plan an algorithm-agility decision for `vvwt-slotopt-dispatcher`. When E37 is resumed or registered, its E37S04 scope MUST reference DEC-43 instead of drafting a parallel decision. The dangling state and coordination requirement are surfaced to the human in this Discovery session's closure.
- External references: RFC 8032 (EdDSA / Ed25519), NIST FIPS 204 (ML-DSA), NIST FIPS 205 (SLH-DSA).
